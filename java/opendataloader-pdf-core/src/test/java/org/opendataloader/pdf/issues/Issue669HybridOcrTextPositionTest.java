/*
 * Copyright 2025-2026 Hancom Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opendataloader.pdf.issues;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;
import org.opendataloader.pdf.hybrid.HybridClientFactory;
import org.opendataloader.pdf.hybrid.HybridConfig;
import org.opendataloader.pdf.processors.DocumentProcessor;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OCR text is added to the page content stream at the position the backend gave, in page
 * coordinates. Where the native text it is anchored to is drawn under {@code q ... cm ... Q}, the
 * matrix of that {@code cm} is in force at the insertion point, and the text must still end up at
 * the position the backend gave. The position is read from the extracted text.
 */
class Issue669HybridOcrTextPositionTest {

    private static final String FIRST = "First native line";
    private static final String OCR = "Recovered from the image";
    /** Where the backend puts the OCR text: left edge and baseline, in page coordinates. */
    private static final float OCR_LEFT = 72;
    private static final float OCR_BASELINE = 482;
    private static final float TOLERANCE = 2;

    @TempDir
    Path tempDir;

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        HybridClientFactory.shutdown();
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        try {
            server.close();
        } finally {
            OpenDataLoaderPDF.shutdown();
        }
    }

    /** Draws the native line, which is at the page position (72, height - 72) however it is drawn. */
    private interface NativeLine {
        void draw(PDPageContentStream content, PDType1Font font, float pageHeight) throws IOException;
    }

    @Test
    void ocrTextIsWhereTheBackendPutItWithNoTransformation() throws Exception {
        assertOcrPosition("plain", (content, font, height) -> {
            show(content, font, 12, 72, height - 72);
        });
    }

    @Test
    void ocrTextIsWhereTheBackendPutItWhenTheNativeLineIsUnderAScalingMatrix() throws Exception {
        assertOcrPosition("scaled", (content, font, height) -> {
            content.saveGraphicsState();
            content.transform(Matrix.getScaleInstance(0.5f, 0.5f));
            show(content, font, 24, 144, (height - 72) / 0.5f);
            content.restoreGraphicsState();
        });
    }

    @Test
    void ocrTextIsWhereTheBackendPutItWhenTheNativeLineIsUnderNestedMatrices() throws Exception {
        assertOcrPosition("nested", (content, font, height) -> {
            content.saveGraphicsState();
            content.transform(Matrix.getScaleInstance(0.5f, 0.5f));
            content.saveGraphicsState();
            content.transform(Matrix.getTranslateInstance(30, 40));
            show(content, font, 24, 144 - 30, (height - 72) / 0.5f - 40);
            content.restoreGraphicsState();
            content.restoreGraphicsState();
        });
    }

    private static void show(PDPageContentStream content, PDType1Font font, float size, float x, float y)
            throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(FIRST);
        content.endText();
    }

    private void assertOcrPosition(String name, NativeLine nativeLine) throws Exception {
        PDRectangle a4 = PDRectangle.A4;
        Path pdf = tempDir.resolve(name + ".pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(a4);
            document.addPage(page);
            BufferedImage raster = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = raster.createGraphics();
            graphics.setColor(new Color(200, 200, 200));
            graphics.fillRect(0, 0, 600, 400);
            graphics.dispose();
            PDImageXObject image = LosslessFactory.createFromImage(document, raster);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                nativeLine.draw(content, font, a4.getHeight());
                content.drawImage(image, 60, 300, 450, 300);
            }
            document.save(pdf.toFile());
        }
        String texts = node(FIRST, 72, a4.getHeight() - 60, 300, a4.getHeight() - 76) + ","
            + node(OCR, OCR_LEFT, 500, 400, OCR_BASELINE);
        server.enqueue(new MockResponse.Builder().code(200).body("ok").build());
        server.enqueue(new MockResponse.Builder()
            .body("{\"status\": \"success\", \"document\": {\"json_content\": {\"texts\": [" + texts + "],"
                + " \"pages\": {\"1\": {}}}}, \"processing_time\": 0.5, \"errors\": [], \"failed_pages\": []}")
            .addHeader("Content-Type", "application/json")
            .build());
        Config config = new Config();
        config.setOutputFolder(tempDir.toString());
        config.setGenerateTaggedPDF(true);
        config.setHybrid("docling-fast");
        config.getHybridConfig().setMode(HybridConfig.MODE_FULL);
        config.getHybridConfig().setUrl(server.url("").toString().replaceAll("/$", ""));

        DocumentProcessor.processFile(pdf.toString(), config);

        Point2D.Float position = positionOfOcrText(tempDir.resolve(name + "_tagged.pdf"), a4.getHeight());
        assertNotNull(position, "the OCR text is not extractable from the tagged PDF");
        assertTrue(Math.abs(position.x - OCR_LEFT) < TOLERANCE && Math.abs(position.y - OCR_BASELINE) < TOLERANCE,
            "the OCR text is at (" + position.x + ", " + position.y + "), not where the backend put it ("
                + OCR_LEFT + ", " + OCR_BASELINE + ")");
    }

    /**
     * @param tagged the converted PDF
     * @param pageHeight the page height, to turn the extractor's top-down y into a baseline
     * @return the left edge and baseline of the OCR text in the extracted text, in page coordinates, or
     *     null when the text is not in the extraction
     */
    private static Point2D.Float positionOfOcrText(Path tagged, float pageHeight) throws IOException {
        List<TextPosition> positions = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(tagged.toFile())) {
            new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition text) {
                    positions.add(text);
                    super.processTextPosition(text);
                }
            }.getText(document);
        }
        StringBuilder all = new StringBuilder();
        for (TextPosition position : positions) {
            all.append(position.getUnicode());
        }
        int index = all.indexOf(OCR);
        if (index < 0) {
            return null;
        }
        TextPosition first = positions.get(index);
        return new Point2D.Float(first.getX(), pageHeight - first.getYDirAdj());
    }

    private static String node(String text, float left, float top, float right, float bottom) {
        return "{\"label\": \"text\", \"text\": \"" + text + "\", \"prov\": [{\"page_no\": 1, \"bbox\": {\"l\": " + left
            + ", \"t\": " + top + ", \"r\": " + right + ", \"b\": " + bottom + ", \"coord_origin\": \"BOTTOMLEFT\"}}]}";
    }
}
