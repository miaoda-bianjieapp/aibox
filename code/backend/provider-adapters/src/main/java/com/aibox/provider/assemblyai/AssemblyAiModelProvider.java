package com.aibox.provider.assemblyai;

import com.aibox.feature.spi.AudioTranscriptionRequest;
import com.aibox.feature.spi.AudioTranscriptionResponse;
import com.aibox.feature.spi.AudioTranscriptSegment;
import com.aibox.feature.spi.AudioTimestampMode;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelCallTarget;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelProviderClient;
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.provider.openai.ModelProviderProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public final class AssemblyAiModelProvider implements ModelProviderClient {

    public static final String PROTOCOL = "assemblyai";

    private static final String UPLOAD_PATH = "/v2/upload";
    private static final String TRANSCRIPT_PATH = "/v2/transcript";
    private static final int DEFAULT_POLL_INTERVAL_MILLIS = 3_000;
    private static final int DEFAULT_POLL_TIMEOUT_SECONDS = 240;
    private static final int DEFAULT_MIN_SPEAKERS_EXPECTED = 1;
    private static final int DEFAULT_MAX_SPEAKERS_EXPECTED = 6;
    private static final String CJK_SCRIPTS =
            "\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}";
    private static final Pattern SPOKEN_DOMAIN_DOTS = Pattern.compile(
            "(?i)(?<![a-z0-9-])((?:[a-z0-9](?:[a-z0-9-]{0,62})\\s*[点點]\\s*)+"
                    + "(?:com|org|net|edu|gov|io|ai|cn|co|me|app|dev|tech|cloud|top|xyz|info|biz|tv|cc))"
                    + "(?![a-z0-9-])"
    );
    private static final Pattern STANDALONE_FILLERS = Pattern.compile(
            "(^|[\\s\\uFF0C\\u3002\\uFF01\\uFF1F\\uFF1B\\uFF1A\\u3001,.!?;:])"
                    + "(?:\\u55EF+|\\u5443+)"
                    + "(?:[\\t \\u00A0]*[\\uFF0C\\u3002\\uFF01\\uFF1F\\uFF1B\\uFF1A\\u3001,.!?;:]+)?"
                    + "(?=$|[\\s\\uFF0C\\u3002\\uFF01\\uFF1F\\uFF1B\\uFF1A\\u3001,.!?;:])"
    );
    private static final Pattern LEADING_AH_FILLER = Pattern.compile(
            "(^|[\\u3002\\uFF01\\uFF1F.!?][\\t \\u00A0]*)"
                    + "\\u554A+(?:[\\t \\u00A0]*"
                    + "[\\uFF0C\\u3002\\uFF01\\uFF1F\\uFF1B\\uFF1A\\u3001,.!?;:]+)?"
                    + "(?=$|[\\s\\uFF0C\\u3002\\uFF01\\uFF1F\\uFF1B\\uFF1A\\u3001,.!?;:])"
    );
    private static final Pattern FILLER_WORD = Pattern.compile(
            "^(?:\\u55EF+|\\u5443+)"
                    + "[\\uFF0C\\u3002\\uFF01\\uFF1F\\uFF1B\\uFF1A\\u3001,.!?;:]*$"
    );
    private static final Pattern LEADING_AH_WORD = Pattern.compile(
            "^\\u554A+[\\uFF0C\\u3002\\uFF01\\uFF1F\\uFF1B\\uFF1A\\u3001,.!?;:]*$"
    );
    private static final Pattern INTRA_CJK_SPACES = Pattern.compile(
            "(?<=[" + CJK_SCRIPTS + "])[\\t \\u00A0]+(?=[" + CJK_SCRIPTS + "])"
    );
    private static final Pattern CJK_BEFORE_PUNCTUATION_SPACES = Pattern.compile(
            "(?<=[" + CJK_SCRIPTS + "])[\\t \\u00A0]+"
                    + "(?=[\\uFF0C\\u3002\\uFF01\\uFF1F\\uFF1B\\uFF1A\\u3001])"
    );
    private static final Pattern PUNCTUATION_BEFORE_CJK_SPACES = Pattern.compile(
            "(?<=[\\uFF0C\\u3002\\uFF01\\uFF1F\\uFF1B\\uFF1A\\u3001])"
                    + "[\\t \\u00A0]+(?=[" + CJK_SCRIPTS + "])"
    );
    private static final Pattern REPEATED_HORIZONTAL_SPACES = Pattern.compile("[\\t \\u00A0]{2,}");

    private final Map<String, ProviderContext> providers;

    public AssemblyAiModelProvider(ModelProviderProperties properties) {
        Map<String, ProviderContext> configured = new LinkedHashMap<>();
        properties.getProviders().forEach((code, config) -> {
            if (!PROTOCOL.equalsIgnoreCase(config.getProtocol())) return;
            if (isBlank(config.getBaseUrl()) || isBlank(config.getApiKey())) {
                throw new IllegalStateException("Provider " + code + " requires base-url and api-key");
            }
            RestClient client = RestClient.builder()
                    .baseUrl(stripTrailingSlash(config.getBaseUrl()))
                    .defaultHeader(HttpHeaders.AUTHORIZATION, config.getApiKey())
                    .defaultHeaders(headers -> config.getHeaders().forEach(headers::set))
                    .build();
            configured.put(code, new ProviderContext(code, client));
        });
        this.providers = Map.copyOf(configured);
    }

    @Override
    public String adapterCode() {
        return PROTOCOL;
    }

    @Override
    public boolean supports(ModelCallTarget target) {
        return target.capability() == ModelCapability.AUDIO_TRANSCRIPTION
                && providers.containsKey(target.providerCode());
    }

    @Override
    public TextGenerationResponse generateText(ModelCallTarget target, TextGenerationRequest request) {
        throw new ModelProviderException(
                "MODEL_CAPABILITY_NOT_SUPPORTED",
                "AssemblyAI adapter does not support " + target.capability(),
                false
        );
    }

    @Override
    public AudioTranscriptionResponse transcribeAudio(
            ModelCallTarget target,
            AudioTranscriptionRequest request,
            ModelAsset asset
    ) {
        ProviderContext provider = requireProvider(target);
        JsonNode upload = execute(() -> provider.client().post()
                .uri(UPLOAD_PATH)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(asset.content())
                .retrieve()
                .body(JsonNode.class));
        String uploadUrl = text(upload, "upload_url");
        if (isBlank(uploadUrl)) {
            throw invalidResponse("AssemblyAI upload response has no upload_url");
        }

        JsonNode transcript = submit(() -> provider.client().post()
                .uri(TRANSCRIPT_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(transcriptBody(target, request, uploadUrl))
                .retrieve()
                .body(JsonNode.class));
        String transcriptId = text(transcript, "id");
        if (isBlank(transcriptId)) {
            throw invalidResponse("AssemblyAI transcript response has no id");
        }

        JsonNode completed = awaitCompletion(provider, target, transcriptId, transcript);
        String rawTranscriptText = text(completed, "text");
        if (rawTranscriptText == null) {
            throw invalidResponse("AssemblyAI completed transcript has no text");
        }
        String model = text(completed, "speech_model_used");
        Double audioDurationSeconds = durationSeconds(completed);
        List<AudioTranscriptSegment> segments = transcriptSegments(
                completed, request, rawTranscriptText, audioDurationSeconds
        );
        String transcriptText = segments.isEmpty()
                ? cleanTranscriptText(rawTranscriptText)
                : String.join(
                        "\n",
                        segments.stream().map(AudioTranscriptSegment::text).toList()
                );
        return new AudioTranscriptionResponse(
                transcriptText,
                segments,
                text(completed, "language_code"),
                audioDurationSeconds,
                provider.code(),
                isBlank(model) ? target.providerModel() : model,
                transcriptId,
                durationUnits(completed),
                null
        );
    }

    private static Map<String, Object> transcriptBody(
            ModelCallTarget target,
            AudioTranscriptionRequest request,
            String uploadUrl
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("audio_url", uploadUrl);
        body.put("speech_models", speechModels(target));
        if (isBlank(request.language()) || "auto".equalsIgnoreCase(request.language())) {
            body.put("language_detection", true);
        } else {
            body.put("language_code", request.language().trim().toLowerCase(Locale.ROOT));
        }
        if (request.speakerDiarization()) {
            body.put("speaker_labels", true);
            int minimum = intSetting(
                    target,
                    "minSpeakersExpected",
                    DEFAULT_MIN_SPEAKERS_EXPECTED,
                    1,
                    10
            );
            int maximum = intSetting(
                    target,
                    "maxSpeakersExpected",
                    DEFAULT_MAX_SPEAKERS_EXPECTED,
                    1,
                    10
            );
            if (minimum > maximum) {
                throw new ModelProviderException(
                        "MODEL_CONFIGURATION_INVALID",
                        "AssemblyAI minimum expected speakers cannot exceed maximum",
                        false
                );
            }
            body.put(
                    "speaker_options",
                    Map.of(
                            "min_speakers_expected", minimum,
                            "max_speakers_expected", maximum
                    )
            );
        }

        String promptMode = stringSetting(target, "promptMode", "keyterms");
        if ("contextual".equalsIgnoreCase(promptMode) && !isBlank(request.prompt())) {
            body.put("prompt", request.prompt().trim());
        } else if (!"keyterms".equalsIgnoreCase(promptMode)) {
            throw new ModelProviderException(
                    "MODEL_CONFIGURATION_INVALID",
                    "AssemblyAI deployment has an unsupported prompt mode",
                    false
            );
        }

        List<String> keyterms = keyterms(request, "keyterms".equalsIgnoreCase(promptMode));
        int maximum = intSetting(
                target,
                "maxKeyterms",
                "universal-2".equalsIgnoreCase(target.providerModel()) ? 200 : 1_000,
                1,
                1_000
        );
        if (keyterms.size() > maximum) {
            throw new ModelProviderException(
                    "MODEL_PROMPT_INVALID",
                    "Professional term hints exceed the selected model limit of " + maximum,
                    false
            );
        }
        if (!keyterms.isEmpty()) body.put("keyterms_prompt", keyterms);
        return Map.copyOf(body);
    }

    private static List<String> speechModels(ModelCallTarget target) {
        Object configured = target.settings().get("speechModels");
        if (configured == null) return List.of(target.providerModel());
        List<String> result = new java.util.ArrayList<>();
        if (configured instanceof Iterable<?> values) {
            values.forEach(value -> {
                if (value != null && !value.toString().isBlank()) result.add(value.toString().trim());
            });
        } else {
            for (String value : configured.toString().split(",")) {
                if (!value.isBlank()) result.add(value.trim());
            }
        }
        if (result.isEmpty()) {
            throw new ModelProviderException(
                    "MODEL_CONFIGURATION_INVALID",
                    "AssemblyAI deployment must configure at least one speech model",
                    false
            );
        }
        return List.copyOf(result);
    }

    private static List<String> keyterms(AudioTranscriptionRequest request, boolean includePrompt) {
        Set<String> result = new LinkedHashSet<>();
        addMetadataTerms(result, request.metadata().get("keyterms"));
        addMetadataTerms(result, request.metadata().get("glossary"));
        if (includePrompt && !isBlank(request.prompt())) {
            for (String term : request.prompt().split("[,，;；\\r\\n]+")) {
                addTerm(result, term);
            }
        }
        return List.copyOf(result);
    }

    private static void addMetadataTerms(Set<String> destination, Object value) {
        if (value instanceof Iterable<?> values) {
            values.forEach(item -> addTerm(destination, item == null ? null : item.toString()));
        } else if (value != null) {
            for (String term : value.toString().split("[,，;；\\r\\n]+")) {
                addTerm(destination, term);
            }
        }
    }

    private static void addTerm(Set<String> destination, String value) {
        if (isBlank(value)) return;
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.split(" ").length > 6) {
            throw new ModelProviderException(
                    "MODEL_PROMPT_INVALID",
                    "Each professional term hint may contain at most six words",
                    false
            );
        }
        destination.add(normalized);
    }

    private static JsonNode awaitCompletion(
            ProviderContext provider,
            ModelCallTarget target,
            String transcriptId,
            JsonNode initial
    ) {
        JsonNode current = initial;
        int intervalMillis = intSetting(
                target,
                "pollIntervalMillis",
                DEFAULT_POLL_INTERVAL_MILLIS,
                1,
                60_000
        );
        int timeoutSeconds = intSetting(
                target,
                "pollTimeoutSeconds",
                DEFAULT_POLL_TIMEOUT_SECONDS,
                1,
                3_600
        );
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        while (true) {
            String status = text(current, "status");
            if ("completed".equalsIgnoreCase(status)) return current;
            if ("error".equalsIgnoreCase(status)) {
                throw new ModelProviderException(
                        "PROVIDER_TRANSCRIPTION_FAILED",
                        "AssemblyAI could not transcribe the submitted audio",
                        false
                );
            }
            if (!"queued".equalsIgnoreCase(status) && !"processing".equalsIgnoreCase(status)) {
                throw invalidResponse("AssemblyAI transcript has an unknown status");
            }
            if (System.nanoTime() >= deadline) {
                throw new ModelProviderException(
                        "PROVIDER_TRANSCRIPTION_TIMEOUT",
                        "AssemblyAI transcription did not finish before the polling timeout",
                        false
                );
            }
            sleep(intervalMillis);
            try {
                current = execute(() -> provider.client().get()
                        .uri(TRANSCRIPT_PATH + "/{id}", transcriptId)
                        .retrieve()
                        .body(JsonNode.class));
            } catch (ModelProviderException exception) {
                if (!exception.retryable()) throw exception;
            }
        }
    }

    private ProviderContext requireProvider(ModelCallTarget target) {
        ProviderContext provider = providers.get(target.providerCode());
        if (provider == null) {
            throw new ModelProviderException(
                    "MODEL_PROVIDER_NOT_CONFIGURED",
                    "AssemblyAI provider is not configured",
                    false
            );
        }
        return provider;
    }

    private static <T> T execute(java.util.function.Supplier<T> call) {
        try {
            T result = call.get();
            if (result == null) throw invalidResponse("AssemblyAI returned an empty response");
            return result;
        } catch (RestClientResponseException exception) {
            throw mapHttpFailure(exception.getStatusCode().value(), exception);
        } catch (ResourceAccessException exception) {
            throw new ModelProviderException(
                    "PROVIDER_CONNECTION_FAILED",
                    "AssemblyAI could not be reached",
                    true,
                    exception
            );
        }
    }

    private static <T> T submit(java.util.function.Supplier<T> call) {
        try {
            T result = call.get();
            if (result == null) throw invalidResponse("AssemblyAI returned an empty submission response");
            return result;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 403 || status == 429) throw mapHttpFailure(status, exception);
            ModelProviderException mapped = mapHttpFailure(status, exception);
            if (!mapped.retryable()) throw mapped;
            throw uncertainSubmission(exception);
        } catch (ResourceAccessException exception) {
            throw uncertainSubmission(exception);
        }
    }

    static ModelProviderException mapHttpFailure(int status, Throwable cause) {
        return switch (status) {
            case 400, 422 -> new ModelProviderException(
                    "PROVIDER_REQUEST_INVALID", "AssemblyAI rejected the transcription request", false, cause
            );
            case 401 -> new ModelProviderException(
                    "PROVIDER_AUTH_FAILED", "AssemblyAI authentication failed", false, cause
            );
            case 403, 408, 429 -> new ModelProviderException(
                    "PROVIDER_RATE_LIMITED", "AssemblyAI is busy; retry later", true, cause
            );
            case 413 -> new ModelProviderException(
                    "PROVIDER_FILE_TOO_LARGE", "The audio file exceeds AssemblyAI limits", false, cause
            );
            case 415 -> new ModelProviderException(
                    "PROVIDER_ASSET_TYPE_UNSUPPORTED", "AssemblyAI does not support this audio format", false, cause
            );
            default -> new ModelProviderException(
                    "PROVIDER_HTTP_" + status,
                    "AssemblyAI request failed with HTTP " + status,
                    status >= 500,
                    cause
            );
        };
    }

    private static ModelProviderException uncertainSubmission(Throwable cause) {
        return new ModelProviderException(
                "PROVIDER_SUBMISSION_UNCERTAIN",
                "AssemblyAI did not confirm whether the transcription request was accepted",
                false,
                cause
        );
    }

    private static Integer durationUnits(JsonNode response) {
        Double seconds = durationSeconds(response);
        if (seconds == null) return null;
        return (int) Math.ceil(seconds);
    }

    private static Double durationSeconds(JsonNode response) {
        JsonNode duration = response.path("audio_duration");
        if (!duration.isNumber()) return null;
        double seconds = duration.asDouble();
        if (!Double.isFinite(seconds) || seconds < 0) return null;
        return seconds;
    }

    private static List<AudioTranscriptSegment> transcriptSegments(
            JsonNode response,
            AudioTranscriptionRequest request,
            String transcriptText,
            Double audioDurationSeconds
    ) {
        if (request.timestampMode() == AudioTimestampMode.SEGMENT || request.speakerDiarization()) {
            List<AudioTranscriptSegment> words = groupWords(response.path("words"));
            if (!words.isEmpty()) return words;
        }
        if (request.speakerDiarization()) {
            List<AudioTranscriptSegment> utterances = mapSegments(response.path("utterances"));
            if (!utterances.isEmpty()) return utterances;
        }
        if (request.speakerDiarization() && !isBlank(transcriptText)) {
            return List.of(new AudioTranscriptSegment(
                    0L,
                    audioDurationSeconds == null ? 0L : Math.round(audioDurationSeconds * 1_000),
                    cleanTranscriptText(transcriptText),
                    null,
                    null
            ));
        }
        return List.of();
    }

    private static List<AudioTranscriptSegment> mapSegments(JsonNode values) {
        if (!values.isArray()) return List.of();
        List<AudioTranscriptSegment> result = new java.util.ArrayList<>();
        values.forEach(value -> {
            String segmentText = cleanTranscriptText(text(value, "text"));
            if (isBlank(segmentText)) return;
            result.add(new AudioTranscriptSegment(
                    nonNegativeLong(value, "start"),
                    nonNegativeLong(value, "end"),
                    segmentText,
                    text(value, "speaker"),
                    confidence(value)
            ));
        });
        return List.copyOf(result);
    }

    private static List<AudioTranscriptSegment> groupWords(JsonNode words) {
        if (!words.isArray()) return List.of();
        List<AudioTranscriptSegment> result = new java.util.ArrayList<>();
        SegmentBuilder segment = new SegmentBuilder();
        words.forEach(word -> {
            String wordText = text(word, "text");
            if (isBlank(wordText)) return;
            if (isFillerWord(wordText, segment.hasText())) return;
            String speaker = text(word, "speaker");
            long start = nonNegativeLong(word, "start");
            long end = nonNegativeLong(word, "end");
            if (segment.hasText() && segment.shouldBreak(speaker, start)) {
                result.add(segment.build());
                segment.reset();
            }
            segment.append(wordText, speaker, start, end, confidence(word));
            if (sentenceEnding(wordText)) {
                result.add(segment.build());
                segment.reset();
            }
        });
        if (segment.hasText()) result.add(segment.build());
        return List.copyOf(result);
    }

    private static String cleanTranscriptText(String value) {
        if (isBlank(value)) return value;
        String normalized = normalizeSpokenDomainDots(value);
        normalized = STANDALONE_FILLERS.matcher(normalized).replaceAll("$1");
        normalized = LEADING_AH_FILLER.matcher(normalized).replaceAll("$1");
        normalized = INTRA_CJK_SPACES.matcher(normalized).replaceAll("");
        normalized = CJK_BEFORE_PUNCTUATION_SPACES.matcher(normalized).replaceAll("");
        normalized = PUNCTUATION_BEFORE_CJK_SPACES.matcher(normalized).replaceAll("");
        normalized = REPEATED_HORIZONTAL_SPACES.matcher(normalized).replaceAll(" ");
        return normalized.trim();
    }

    private static boolean isFillerWord(String value, boolean segmentHasText) {
        if (value == null) return false;
        String normalized = value.trim();
        if (FILLER_WORD.matcher(normalized).matches()) return true;
        return !segmentHasText && LEADING_AH_WORD.matcher(normalized).matches();
    }

    private static String normalizeSpokenDomainDots(String value) {
        if (isBlank(value)) return value;
        Matcher matcher = SPOKEN_DOMAIN_DOTS.matcher(value);
        StringBuffer normalized = new StringBuffer();
        while (matcher.find()) {
            String domain = matcher.group(1).replaceAll("\\s*[点點]\\s*", ".");
            matcher.appendReplacement(normalized, Matcher.quoteReplacement(domain));
        }
        matcher.appendTail(normalized);
        return normalized.toString();
    }

    private static long nonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? Math.max(0L, value.asLong()) : 0L;
    }

    private static Double confidence(JsonNode node) {
        JsonNode value = node.path("confidence");
        if (!value.isNumber()) return null;
        double parsed = value.asDouble();
        return Double.isFinite(parsed) && parsed >= 0 && parsed <= 1 ? parsed : null;
    }

    private static boolean sentenceEnding(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return false;
        char last = trimmed.charAt(trimmed.length() - 1);
        return ".!?。！？".indexOf(last) >= 0;
    }

    private static boolean shouldInsertSpace(CharSequence previous, String next) {
        if (previous.isEmpty() || next.isEmpty()) return false;
        char previousLast = previous.charAt(previous.length() - 1);
        char nextFirst = next.charAt(0);
        if (",.!?;:，。！？；：、)]}》】".indexOf(nextFirst) >= 0) return false;
        if ("([{《【".indexOf(previousLast) >= 0) return false;
        return !isCjk(previousLast) && !isCjk(nextFirst);
    }

    private static boolean isCjk(char value) {
        Character.UnicodeScript script = Character.UnicodeScript.of(value);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static String stringSetting(ModelCallTarget target, String name, String fallback) {
        Object value = target.settings().get(name);
        return value == null || value.toString().isBlank() ? fallback : value.toString().trim();
    }

    private static int intSetting(
            ModelCallTarget target,
            String name,
            int fallback,
            int minimum,
            int maximum
    ) {
        Object value = target.settings().get(name);
        int parsed;
        try {
            parsed = value instanceof Number number
                    ? number.intValue()
                    : value == null ? fallback : Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            throw new ModelProviderException(
                    "MODEL_CONFIGURATION_INVALID",
                    "AssemblyAI deployment setting " + name + " is invalid",
                    false,
                    exception
            );
        }
        return Math.max(minimum, Math.min(maximum, parsed));
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelProviderException(
                    "PROVIDER_TRANSCRIPTION_INTERRUPTED",
                    "AssemblyAI transcription polling was interrupted",
                    false,
                    exception
            );
        }
    }

    private static ModelProviderException invalidResponse(String message) {
        return new ModelProviderException("PROVIDER_INVALID_RESPONSE", message, false);
    }

    private static String stripTrailingSlash(String value) {
        String normalized = value.trim();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ProviderContext(String code, RestClient client) {
    }

    private static final class SegmentBuilder {
        private final StringBuilder text = new StringBuilder();
        private String speaker;
        private long startMs;
        private long endMs;
        private double confidenceTotal;
        private int confidenceCount;

        boolean hasText() {
            return !text.isEmpty();
        }

        boolean shouldBreak(String nextSpeaker, long nextStartMs) {
            return !java.util.Objects.equals(speaker, nextSpeaker)
                    || text.length() >= 120
                    || nextStartMs - startMs >= 30_000;
        }

        void append(String value, String valueSpeaker, long valueStartMs, long valueEndMs, Double confidence) {
            if (text.isEmpty()) {
                startMs = valueStartMs;
                speaker = valueSpeaker;
            } else if (shouldInsertSpace(text, value)) {
                text.append(' ');
            }
            text.append(value.trim());
            endMs = Math.max(valueEndMs, startMs);
            if (confidence != null) {
                confidenceTotal += confidence;
                confidenceCount++;
            }
        }

        AudioTranscriptSegment build() {
            return new AudioTranscriptSegment(
                    startMs,
                    endMs,
                    cleanTranscriptText(text.toString()),
                    speaker,
                    confidenceCount == 0 ? null : confidenceTotal / confidenceCount
            );
        }

        void reset() {
            text.setLength(0);
            speaker = null;
            startMs = 0;
            endMs = 0;
            confidenceTotal = 0;
            confidenceCount = 0;
        }
    }
}
