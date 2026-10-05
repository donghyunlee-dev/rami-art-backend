package com.ramiart.admin.galleryartwork.application;
public final class GalleryArtworkException extends RuntimeException {
    private final String code; private final String field;
    public GalleryArtworkException(String code){this(code,null);} public GalleryArtworkException(String code,String field){super(code);this.code=code;this.field=field;}
    public String code(){return code;} public String field(){return field;}
}
