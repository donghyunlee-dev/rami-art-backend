package com.ramiart.admin.financeimport.application;

public interface FinancialImportStorage {
    void upload(String key,byte[] file);
    byte[] download(String key);
    void delete(String key);
}
