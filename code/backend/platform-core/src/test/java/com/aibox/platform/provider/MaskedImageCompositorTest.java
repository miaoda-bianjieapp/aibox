package com.aibox.platform.provider;

import com.aibox.feature.spi.GeneratedImage;
import com.aibox.feature.spi.ImageGenerationResponse;
import com.aibox.feature.spi.ModelAsset;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MaskedImageCompositorTest {

    @Test
    void preservesOpaqueMaskPixelsAndUsesGeneratedTransparentRegion() throws IOException {
        BufferedImage source = solidImage(2, 1, Color.RED);
        BufferedImage generated = solidImage(2, 1, Color.BLUE);
        BufferedImage mask = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        mask.setRGB(0, 0, new Color(255, 255, 255, 255).getRGB());
        mask.setRGB(1, 0, new Color(255, 255, 255, 0).getRGB());

        ImageGenerationResponse response = MaskedImageCompositor.preserveUnmaskedPixels(
                new ImageGenerationResponse(
                        List.of(new GeneratedImage(null, "image/png", null, png(generated))),
                        "provider",
                        "model",
                        "request-id",
                        1,
                        1
                ),
                new ModelAsset(UUID.randomUUID(), "source.png", "image/png", png(source)),
                new ModelAsset(UUID.randomUUID(), "mask.png", "image/png", png(mask))
        );

        BufferedImage output = ImageIO.read(new ByteArrayInputStream(
                response.images().get(0).content()
        ));
        assertThat(output.getRGB(0, 0)).isEqualTo(Color.RED.getRGB());
        assertThat(output.getRGB(1, 0)).isEqualTo(Color.BLUE.getRGB());
        assertThat(response.images().get(0).mediaType()).isEqualTo("image/png");
    }

    private static BufferedImage solidImage(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        return image;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
