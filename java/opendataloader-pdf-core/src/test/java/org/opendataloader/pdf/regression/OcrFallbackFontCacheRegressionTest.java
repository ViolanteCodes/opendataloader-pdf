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
package org.opendataloader.pdf.regression;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;
import org.opendataloader.pdf.hybrid.HybridClientFactory;
import org.opendataloader.pdf.hybrid.HybridConfig;
import org.opendataloader.pdf.processors.DocumentProcessor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the {@code /ODLOcrFallbackHelv} font-resolution NullPointerException fixed
 * in {@code ensureOcrFallbackFont} (branch {@code fix/ocr-fallback-font-cache-npe}).
 *
 * <p>{@code ensureOcrFallbackFont} used to check whether its reserved synthesized OCR-fallback
 * font was already present via {@code PDResources#getFont(OCR_FALLBACK_FONT_NAME) != null},
 * *before* adding the font. That premature query caused {@code PDResources} to cache a
 * negative/stale lookup result for the name — so a later call on the exact same
 * {@code PDResources} instance (from {@code AutoTaggingProcessor.updatePages}'s tagging pass),
 * made *after* the font had genuinely been added to the underlying COS {@code /Font} dictionary,
 * still reported it missing. That resolved to a null {@code PDFont}, which
 * {@code ChunksWriter.processString} then dereferenced unconditionally, crashing with an NPE —
 * confirmed via targeted diagnostic logging against a real reproducing document (identical
 * {@code PDPage}/{@code PDResources} object identity in both phases, font still unresolved at
 * tagging time). The fix reads the raw COS {@code /Font} dictionary directly for the presence
 * check instead, so {@code PDResources#getFont} is never queried for this name before it exists.
 *
 * <p>Replays a real, captured {@code /v1/convert/file} response from the
 * {@code opendataloader-pdf-hybrid} backend for a real reproducing 4-page slice (a compiled
 * document packet mixing a native-text CV page with a browser-screenshotted webpage, heavy on
 * OCR-fallback content with a badly fragmented, letter-by-letter recovered text pattern — see
 * {@code dice-document-pipeline-api}'s {@code implementation_plan.md}) via {@link MockWebServer},
 * so the test needs no live hybrid server and is fully deterministic. Mirrors
 * {@code HybridOcrFallbackOrderingRegressionTest}'s pattern for the same reason: this bug class
 * only manifests when the backend processes multiple pages together, not a single page in
 * isolation (confirmed empirically this session — isolating the crashing page alone, even forced
 * through {@code --hybrid-mode full}, does not reproduce it), so the fixture must stay multi-page.
 */
class OcrFallbackFontCacheRegressionTest {

    private static final String SAMPLE_PDF = "src/test/resources/hybrid/pages11-14.pdf";
    private static final String SAMPLE_HYBRID_RESPONSE =
        "src/test/resources/hybrid/pages11-14-hybrid-response.json";

    @TempDir
    Path tempDir;

    private MockWebServer server;
    private File samplePdf;

    @BeforeEach
    void setUp() throws IOException {
        // See HybridBackendFailureIntegrationTest: HybridClientFactory caches clients
        // process-wide by backend name, so clear it before pointing docling-fast at
        // this test's MockWebServer URL.
        HybridClientFactory.shutdown();

        server = new MockWebServer();
        server.start();
        samplePdf = new File(SAMPLE_PDF);
        assertTrue(samplePdf.exists(), "Sample PDF not found at " + samplePdf.getAbsolutePath());
    }

    @AfterEach
    void tearDown() throws IOException {
        try {
            if (server != null) {
                server.close();
            }
        } finally {
            OpenDataLoaderPDF.shutdown();
        }
    }

    @Test
    void ocrFallbackFontResolvesAtTaggingTime_doesNotCrash() throws IOException {
        // /health probe (Phase 0 checkAvailability)
        server.enqueue(new MockResponse.Builder().code(200).body("ok").build());
        // /v1/convert/file — a real, captured backend response for this fixture (MODE_FULL below
        // makes backend routing deterministic instead of depending on triage heuristics, which
        // this session found to be sensitive to document-wide context and not reproducible from
        // a single isolated page).
        String hybridResponseJson = Files.readString(Paths.get(SAMPLE_HYBRID_RESPONSE));
        server.enqueue(new MockResponse.Builder()
            .body(hybridResponseJson)
            .addHeader("Content-Type", "application/json")
            .build());

        Config config = new Config();
        config.setOutputFolder(tempDir.toString());
        config.setGenerateTaggedPDF(true);
        config.setHybrid("docling-fast");
        config.getHybridConfig().setMode(HybridConfig.MODE_FULL);
        config.getHybridConfig().setUrl(server.url("").toString().replaceAll("/$", ""));

        // Before the fix: this throws NullPointerException from ChunksWriter.processString via
        // AutoTaggingProcessor.updatePages. No assertion needed for that — a regression shows up
        // as this call itself throwing, failing the test with a stack trace pointing right back
        // at the bug this test guards.
        DocumentProcessor.processFile(samplePdf.getAbsolutePath(), config);

        Path taggedPdf = tempDir.resolve("pages11-14_tagged.pdf");
        assertTrue(Files.exists(taggedPdf), "Expected a tagged PDF at " + taggedPdf);

        try (PDDocument doc = Loader.loadPDF(taggedPdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String allText = stripper.getText(doc);

            // The crash previously happened while writing this exact invisible (Tr 3)
            // OCR-fallback text — assert it actually made it into the tagged output rather than
            // just confirming the process didn't crash. A defensive null-guard elsewhere could
            // stop the crash by silently dropping this content instead of fixing font
            // resolution — that would defeat the whole point of the OCR-fallback feature for
            // exactly the content it exists to serve, so "no crash" alone isn't a sufficient
            // regression guard here.
            assertTrue(allText.contains("Accessibility Departments Announcements"),
                "Previously-crashing OCR-fallback text is missing from the tagged output — "
                + "the crash may have been avoided by silently dropping the content instead of "
                + "fixing font resolution.\nallText=" + allText);
            assertFalse(allText.isEmpty(), "Tagged output text is unexpectedly empty");
        }
    }
}
