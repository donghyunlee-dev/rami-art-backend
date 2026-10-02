package com.ramiart.admin.common.api;

import java.util.List;

public record ApiEnvelope<T>(
        boolean success,
        T data,
        ApiError error,
        String requestId) {

    public static <T> ApiEnvelope<T> success(T data, String requestId) {
        return new ApiEnvelope<>(true, data, null, requestId);
    }

    public static ApiEnvelope<Void> failure(String code, String message, List<FieldError> fieldErrors, String requestId) {
        return new ApiEnvelope<>(false, null, new ApiError(code, message, fieldErrors), requestId);
    }

    public record ApiError(String code, String message, List<FieldError> fieldErrors) {
    }

    public record FieldError(String field, String code, String message) {
    }
}
