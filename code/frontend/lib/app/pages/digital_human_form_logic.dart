class DigitalHumanFormLogic {
  const DigitalHumanFormLogic._();

  static const featureCode = 'video.digital_human';
  static const avatarUpload = 'UPLOAD';
  static const avatarHistory = 'HISTORY';
  static const avatarGenerated = 'AI_GENERATED';
  static const audioText = 'TEXT_TO_SPEECH';
  static const audioUpload = 'UPLOAD_AUDIO';

  static bool isDigitalHuman(String code) =>
      code == DigitalHumanFormLogic.featureCode;

  static bool canConfirmAvatar({
    required String? source,
    required bool hasImage,
    required String prompt,
  }) {
    if (source == avatarGenerated && prompt.trim().isEmpty) return false;
    return hasImage &&
        (source == avatarUpload ||
            source == avatarHistory ||
            source == avatarGenerated);
  }

  static bool canConfirmAudio({
    required String? source,
    required bool hasAudio,
    required String script,
  }) {
    if (source == audioText) return script.trim().isNotEmpty;
    return source == audioUpload && hasAudio;
  }

  static int estimateSpeechSeconds(String script, {double speed = 1.0}) {
    final normalizedSpeed = speed.clamp(0.5, 2.0);
    final meaningful = script.runes
        .map(String.fromCharCode)
        .where((character) => !RegExp(r'\s').hasMatch(character))
        .length;
    if (meaningful == 0) return 0;
    return (meaningful / (4.0 * normalizedSpeed)).ceil();
  }

  static String fixedAvatarPrompt(String userPrompt) {
    const prefix =
        '\u5355\u4eba\u771f\u5b9e\u4eba\u7269\u8096\u50cf\uff0c\u5934\u80a9\u6784\u56fe\uff0c\u6b63\u9762\u6216\u4e09\u5206\u4e4b\u4e8c\u4fa7\u8138\uff0c\u9762\u90e8\u5b8c\u6574\u6e05\u6670\uff0c\u53cc\u773c\u548c\u5634\u90e8\u6e05\u695a\u53ef\u89c1\uff0c';
    const constraints =
        '\u81ea\u7136\u76ae\u80a4\u548c\u771f\u5b9e\u5149\u7ebf\uff0c\u7b80\u6d01\u5e72\u51c0\u80cc\u666f\uff0c\u65e0\u6587\u5b57\uff0c\u65e0\u6c34\u5370\uff0c\u65e0\u591a\u4eba\uff0c\u65e0\u52a8\u7269\uff0c\u9002\u5408\u6570\u5b57\u4eba\u53e3\u64ad\u548c\u53e3\u578b\u540c\u6b65\u3002';
    final cleaned = userPrompt.trim();
    const remaining = 500 - prefix.length - constraints.length - 2;
    final description =
        cleaned.length > remaining ? cleaned.substring(0, remaining) : cleaned;
    return '$prefix$description\u3002$constraints';
  }

  static bool isSourceMode(String source, String mode) => source == mode;
}
