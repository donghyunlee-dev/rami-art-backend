package com.rami.artstudio.gallery.storage;

import org.springframework.web.multipart.MultipartFile;

public interface GalleryImageStorage {
    UploadedImage upload(MultipartFile file);

    void delete(String imagePath);

    record UploadedImage(String imageUrl, String imagePath) {
    }
}
