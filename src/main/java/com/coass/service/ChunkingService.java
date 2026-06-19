package com.coass.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChunkingService {

    private static final int CHUNK_SIZE = 1000;
    private static final int OVERLAP    = 200;

    public List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        int len = text.length();
        int start = 0;

        while (start < len) {
            int end = Math.min(start + CHUNK_SIZE, len);

            // rozszerz do granicy słowa
            if (end < len) {
                int nextSpace = text.indexOf(' ', end);
                if (nextSpace != -1 && nextSpace - end < 50) {
                    end = nextSpace;
                }
            }

            chunks.add(text.substring(start, end).trim());

            if (end >= len) break;

            start = end - OVERLAP;
        }
        return chunks;
    }
}
