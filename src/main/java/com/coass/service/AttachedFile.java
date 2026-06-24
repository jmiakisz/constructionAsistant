package com.coass.service;

public record AttachedFile(
        String fileName,
        String mediaType,
        String base64Data
) {
    public boolean isImage() {
        return mediaType != null && mediaType.startsWith("image/");
    }

    public boolean isPdf() {
        return "application/pdf".equals(mediaType);
    }
}
