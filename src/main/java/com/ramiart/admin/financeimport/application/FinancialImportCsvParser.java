package com.ramiart.admin.financeimport.application;

import java.util.ArrayList;
import java.util.List;

public final class FinancialImportCsvParser {
    private FinancialImportCsvParser(){}
    public static List<List<String>> parse(String source){
        List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder field=new StringBuilder();boolean quoted=false,afterQuote=false;
        for(int i=0;i<source.length();i++){
            char c=source.charAt(i);
            if(quoted){if(c=='"'){if(i+1<source.length()&&source.charAt(i+1)=='"'){field.append('"');i++;}else{quoted=false;afterQuote=true;}}else field.append(c);continue;}
            if(afterQuote&&c!=','&&c!='\r'&&c!='\n')throw new CsvFormatException();
            if(c=='"'){if(field.length()!=0||afterQuote)throw new CsvFormatException();quoted=true;}
            else if(c==','){row.add(field.toString());field.setLength(0);afterQuote=false;}
            else if(c=='\r'||c=='\n'){if(c=='\r'&&i+1<source.length()&&source.charAt(i+1)=='\n')i++;row.add(field.toString());field.setLength(0);afterQuote=false;if(!(row.size()==1&&row.getFirst().isEmpty()))rows.add(List.copyOf(row));row.clear();if(rows.size()>5001)throw new CsvFormatException();}
            else field.append(c);
        }
        if(quoted)throw new CsvFormatException();
        if(field.length()>0||!row.isEmpty()||afterQuote){row.add(field.toString());if(!(row.size()==1&&row.getFirst().isEmpty()))rows.add(List.copyOf(row));}
        if(rows.size()>5001)throw new CsvFormatException();
        return List.copyOf(rows);
    }
    public static final class CsvFormatException extends RuntimeException{}
}
