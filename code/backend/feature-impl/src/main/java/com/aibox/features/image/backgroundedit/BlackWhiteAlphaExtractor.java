package com.aibox.features.image.backgroundedit;

import java.awt.image.BufferedImage;

final class BlackWhiteAlphaExtractor {

    private BlackWhiteAlphaExtractor() {
    }

    static BufferedImage extract(BufferedImage white, BufferedImage black) {
        if (white.getWidth() != black.getWidth() || white.getHeight() != black.getHeight()) {
            throw new IllegalArgumentException("black and white images must have identical dimensions");
        }
        BufferedImage result = new BufferedImage(
                white.getWidth(),
                white.getHeight(),
                BufferedImage.TYPE_INT_ARGB
        );
        for (int y = 0; y < white.getHeight(); y++) {
            for (int x = 0; x < white.getWidth(); x++) {
                int whiteRgb = white.getRGB(x, y);
                int blackRgb = black.getRGB(x, y);
                int differenceRed = clamp(
                        ((whiteRgb >>> 16) & 0xFF) - ((blackRgb >>> 16) & 0xFF)
                );
                int differenceGreen = clamp(
                        ((whiteRgb >>> 8) & 0xFF) - ((blackRgb >>> 8) & 0xFF)
                );
                int differenceBlue = clamp((whiteRgb & 0xFF) - (blackRgb & 0xFF));
                int maximumDifference = Math.max(
                        differenceRed,
                        Math.max(differenceGreen, differenceBlue)
                );
                int minimumDifference = Math.min(
                        differenceRed,
                        Math.min(differenceGreen, differenceBlue)
                );
                int alpha = clamp(255 - ((maximumDifference + minimumDifference) / 2));
                int red = recoverForegroundChannel((blackRgb >>> 16) & 0xFF, alpha);
                int green = recoverForegroundChannel((blackRgb >>> 8) & 0xFF, alpha);
                int blue = recoverForegroundChannel(blackRgb & 0xFF, alpha);
                result.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
            }
        }
        return result;
    }

    private static int recoverForegroundChannel(int blackCompositeChannel, int alpha) {
        if (alpha == 0) return 0;
        return clamp((blackCompositeChannel * 255 + alpha / 2) / alpha);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
