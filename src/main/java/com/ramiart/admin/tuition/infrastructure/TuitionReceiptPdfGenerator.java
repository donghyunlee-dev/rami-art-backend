package com.ramiart.admin.tuition.infrastructure;

import com.ramiart.admin.tuition.application.TuitionReceiptRepository.Source;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

@Component
public final class TuitionReceiptPdfGenerator {
    private static final DateTimeFormatter ISSUED=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"));
    public byte[] generate(String receiptNumber,int version,Source source,Map<String,Object> snapshot){
        try(PDDocument document=new PDDocument();ByteArrayOutputStream output=new ByteArrayOutputStream();
                InputStream fontStream=getClass().getResourceAsStream("/fonts/NotoSansKR-Regular.ttf")){
            if(fontStream==null)throw new IllegalStateException("Receipt font is missing");
            PDType0Font font=PDType0Font.load(document,fontStream);
            PDPage page=new PDPage(PDRectangle.A4);document.addPage(page);
            try(PDPageContentStream content=new PDPageContentStream(document,page)){
                content.beginText();content.setFont(font,20);content.newLineAtOffset(56,770);content.showText("라미아트미술교습소 수업료 영수증");
                content.setFont(font,11);content.newLineAtOffset(0,-35);content.showText("영수증 번호: "+receiptNumber+"   재발행: "+version+"회");
                content.newLineAtOffset(0,-28);content.showText("발행일: "+ISSUED.format(Instant.parse((String)snapshot.get("issuedAt"))));
                List<String> lines=List.of("교습소: "+snapshot.get("studioName"),"원생: "+snapshot.get("maskedStudentName"),
                        "청구월: "+source.yearMonth(),"납입일: "+source.paidOn(),"납입금액: "+String.format("%,d원",source.amount()),
                        "납입수단: "+method(source.method()),"환불 누계: "+String.format("%,d원",((Number)snapshot.get("refundAmount")).longValue()),
                        "유효 납입액: "+String.format("%,d원",((Number)snapshot.get("netPaidAmount")).longValue()),
                        "납입 상태: "+snapshot.get("paymentStatus"));
                for(String line:lines){content.newLineAtOffset(0,-26);content.showText(line);}
                content.endText();
            }
            document.save(output);return output.toByteArray();
        }catch(Exception e){throw new JdbcTuitionReceiptRepository.TuitionReceiptException("RECEIPT_GENERATION_FAILED",e);}
    }
    private static String method(String method){return switch(method){case "CASH"->"현금";case "TRANSFER"->"계좌이체";case "CARD"->"카드";default->"기타";};}
}
