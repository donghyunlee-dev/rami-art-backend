package com.ramiart.admin.datatransfer.application;

import java.util.ArrayList;
import java.util.List;

public final class DataTransferCsvParser {
    private static final int MAX_ROWS_WITH_HEADER = 10_001;
    private DataTransferCsvParser() {}

    public static List<List<String>> parse(String source) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean afterQuote = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (quoted) {
                if (current == '"') {
                    if (index + 1 < source.length() && source.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else {
                        quoted = false;
                        afterQuote = true;
                    }
                } else {
                    field.append(current);
                }
                continue;
            }
            if (afterQuote && current != ',' && current != '\r' && current != '\n') throw new CsvFormatException();
            if (current == '"') {
                if (!field.isEmpty() || afterQuote) throw new CsvFormatException();
                quoted = true;
            } else if (current == ',') {
                row.add(field.toString());
                field.setLength(0);
                afterQuote = false;
            } else if (current == '\r' || current == '\n') {
                if (current == '\r' && index + 1 < source.length() && source.charAt(index + 1) == '\n') index++;
                row.add(field.toString());
                field.setLength(0);
                afterQuote = false;
                if (!(row.size() == 1 && row.getFirst().isEmpty())) rows.add(List.copyOf(row));
                row.clear();
                if (rows.size() > MAX_ROWS_WITH_HEADER) throw new CsvFormatException();
            } else {
                field.append(current);
            }
        }
        if (quoted) throw new CsvFormatException();
        if (!field.isEmpty() || !row.isEmpty() || afterQuote) {
            row.add(field.toString());
            if (!(row.size() == 1 && row.getFirst().isEmpty())) rows.add(List.copyOf(row));
        }
        if (rows.isEmpty() || rows.size() > MAX_ROWS_WITH_HEADER) throw new CsvFormatException();
        return List.copyOf(rows);
    }

    public static final class CsvFormatException extends RuntimeException {}
}
