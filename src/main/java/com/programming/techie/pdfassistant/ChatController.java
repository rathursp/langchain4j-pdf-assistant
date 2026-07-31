package com.programming.techie.pdfassistant;

import dev.langchain4j.chain.ConversationalRetrievalChain;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.cassandra.AstraDbEmbeddingStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ConversationalRetrievalChain conversationalRetrievalChain;
    private final EmbeddingStoreIngestor embeddingStoreIngestor;
    private final AstraDbEmbeddingStore astraDbEmbeddingStore;

    // tracks whether a PDF has been uploaded in this session
    private boolean pdfIngested = false;

    public ChatController(ConversationalRetrievalChain conversationalRetrievalChain,
                          EmbeddingStoreIngestor embeddingStoreIngestor,
                          AstraDbEmbeddingStore astraDbEmbeddingStore) {
        this.conversationalRetrievalChain = conversationalRetrievalChain;
        this.embeddingStoreIngestor = embeddingStoreIngestor;
        this.astraDbEmbeddingStore = astraDbEmbeddingStore;
    }

    /**
     * POST /api/upload
     * Clears old embeddings from AstraDB, then parses and ingests the new PDF.
     * This ensures questions are always answered from the currently uploaded PDF only.
     */
    @PostMapping("/upload")
    public ResponseEntity<String> uploadPdf(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Please select a PDF file to upload.");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest().body("Only PDF files are supported.");
        }

        try (InputStream inputStream = file.getInputStream()) {
            // Clear all existing embeddings from the table before ingesting the new PDF.
            // This prevents mixing context from multiple PDFs which causes incorrect answers.
            astraDbEmbeddingStore.getEmbeddingTable().clear();

            ApachePdfBoxDocumentParser parser = new ApachePdfBoxDocumentParser();
            Document document = parser.parse(inputStream);
            embeddingStoreIngestor.ingest(document);
            pdfIngested = true;
            return ResponseEntity.ok("PDF \"" + originalFilename + "\" uploaded and indexed successfully. You can now ask questions.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to process PDF: " + e.getMessage());
        }
    }

    /**
     * POST /api/chat
     * Accepts a plain-text question and returns an answer based on the uploaded PDF.
     */
    @PostMapping("/chat")
    public ResponseEntity<String> chat(@RequestBody String question) {
        if (!pdfIngested) {
            return ResponseEntity.badRequest().body("Please upload a PDF first before asking questions.");
        }
        if (question == null || question.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Question cannot be empty.");
        }
        String answer = conversationalRetrievalChain.execute(question.trim());
        return ResponseEntity.ok(answer);
    }

    /**
     * GET /api/status
     * Returns whether a PDF has been uploaded in the current session.
     */
    @GetMapping("/status")
    public ResponseEntity<String> status() {
        return ResponseEntity.ok(pdfIngested ? "ready" : "no_pdf");
    }
}
