package com.aibox.platform.asset;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebpImageIoSupportTest {

    @Test
    void registersAWebpReaderForAssetDimensionInspection() {
        assertTrue(
                ImageIO.getImageReadersByMIMEType("image/webp").hasNext(),
                "The runtime must provide a WebP ImageIO reader"
        );
    }
}
