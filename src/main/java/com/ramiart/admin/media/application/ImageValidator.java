package com.ramiart.admin.media.application;

import com.ramiart.admin.media.application.MediaModels.ValidatedImage;

public interface ImageValidator {
    ValidatedImage validate(byte[] bytes, String fileName, String contentType);
}

