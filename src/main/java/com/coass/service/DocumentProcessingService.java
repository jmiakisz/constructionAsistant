package com.coass.service;

import com.coass.entity.Document;
import com.coass.entity.DocumentStatus;
import com.coass.repository.DocumentChunkRepository;
import com.coass.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.WriteOutContentHandler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessingService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final DocumentAnalysisService analysisService;

    private static final int MAX_TEXT_LENGTH = 300_000;

    @Async
    public void process(Long documentId, Path filePath) {
        try {
            String text = extractText(filePath);

            List<String> chunks = chunkingService.chunk(text);

            Document doc = documentRepository.findById(documentId).orElseThrow();

            log.info("DB INSERT document_chunks documentId={} total={}", doc.getId(), chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                String content = chunks.get(i);
                float[] vector = embeddingService.embed(content);
                String vectorStr = embeddingService.toVectorString(vector);
                chunkRepository.insertWithEmbedding(doc.getId(), content, vectorStr, i);
                log.debug("DB INSERT document_chunks chunkIndex={} contentLength={}", i, content.length());
            }

            doc.setStatus(DocumentStatus.READY.name());
            documentRepository.save(doc);
            log.info("DB UPDATE documents id={} status=READY chunks={}", documentId, chunks.size());

            if (doc.getAiIndexingMode() == com.coass.entity.AiIndexingMode.FULL) {
                log.info("Starting AI analysis for document {}", documentId);
                analysisService.analyzeDocument(documentId, text);
            }

        } catch (Exception e) {
            log.error("Error processing document {}", documentId, e);
            documentRepository.findById(documentId).ifPresent(doc -> {
                doc.setStatus(DocumentStatus.ERROR.name());
                documentRepository.save(doc);
            });
        }
    }

    private String extractText(Path filePath) throws Exception {
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(false);
        pdfConfig.setExtractUniqueInlineImagesOnly(false);

        ParseContext context = new ParseContext();
        context.set(PDFParserConfig.class, pdfConfig);

        WriteOutContentHandler handler = new WriteOutContentHandler(MAX_TEXT_LENGTH);
        Metadata metadata = new Metadata();
        AutoDetectParser parser = new AutoDetectParser();

        try (InputStream stream = new FileInputStream(filePath.toFile())) {
            parser.parse(stream, handler, metadata, context);
        } catch (org.apache.tika.exception.WriteLimitReachedException e) {
            log.warn("Document {} hit text limit ({} chars), truncating", filePath.getFileName(), MAX_TEXT_LENGTH);
        }
        return handler.toString();
    }
}
