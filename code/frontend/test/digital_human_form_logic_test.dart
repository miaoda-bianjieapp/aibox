import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/pages/digital_human_form_logic.dart';

void main() {
  test('avatar confirmation requires the selected source to have an image', () {
    expect(
      DigitalHumanFormLogic.canConfirmAvatar(
        source: DigitalHumanFormLogic.avatarUpload,
        hasImage: false,
        prompt: '',
      ),
      isFalse,
    );
    expect(
      DigitalHumanFormLogic.canConfirmAvatar(
        source: DigitalHumanFormLogic.avatarHistory,
        hasImage: true,
        prompt: '',
      ),
      isTrue,
    );
    expect(
      DigitalHumanFormLogic.canConfirmAvatar(
        source: DigitalHumanFormLogic.avatarGenerated,
        hasImage: true,
        prompt: '',
      ),
      isFalse,
    );
  });

  test('audio confirmation requires text or uploaded audio', () {
    expect(
      DigitalHumanFormLogic.canConfirmAudio(
        source: DigitalHumanFormLogic.audioText,
        hasAudio: false,
        script: '',
      ),
      isFalse,
    );
    expect(
      DigitalHumanFormLogic.canConfirmAudio(
        source: DigitalHumanFormLogic.audioText,
        hasAudio: false,
        script: 'hello',
      ),
      isTrue,
    );
    expect(
      DigitalHumanFormLogic.canConfirmAudio(
        source: DigitalHumanFormLogic.audioUpload,
        hasAudio: true,
        script: '',
      ),
      isTrue,
    );
  });

  test('speech estimate changes with speed and exposes over-limit durations',
      () {
    expect(DigitalHumanFormLogic.estimateSpeechSeconds('12345678'), 2);
    expect(
      DigitalHumanFormLogic.estimateSpeechSeconds('12345678', speed: 2),
      1,
    );
    expect(
      DigitalHumanFormLogic.estimateSpeechSeconds('x' * 1000),
      250,
    );
  });

  test('avatar prompt always contains fixed person constraints', () {
    final prompt = DigitalHumanFormLogic.fixedAvatarPrompt('a detective');
    expect(prompt, contains('\u4eba\u7269'));
    expect(prompt, contains('\u5355\u4eba'));
    expect(prompt, contains('\u53e3\u578b\u540c\u6b65'));
    expect(prompt.length, lessThanOrEqualTo(500));
  });
}
