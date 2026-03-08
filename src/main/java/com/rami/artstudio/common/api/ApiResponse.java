package com.rami.artstudio.common.api;

public record ApiResponse<T>(
        boolean success,
        T data,
        String message,
        ApiError error) {

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(true, data, message, null);
    }

    public static <T> ApiResponse<T> success(T data) {
        return success(data, "처리 완료");
    }

    public static ApiResponse<Void> failure(ErrorCode code, String message) {
        return new ApiResponse<>(false, null, null, new ApiError(code.name(), message));
    }
}
