package com.chengyinhub.web.controller.api;

import org.springframework.web.multipart.MultipartFile;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Iterator;

/**
 * Only the synchronous review copy is bounded; the validated upload is never replaced.
 * Official legacy sync contract: dev/framework/security.imgSecCheck.html (1M, 750x1334).
 */
final class WxImageSecurityCopy {
    static final int MAX_BYTES = 1_000_000;
    static final int MAX_WIDTH = 750;
    static final int MAX_HEIGHT = 1334;
    private final byte[] bytes;
    private final String extension;
    private WxImageSecurityCopy(byte[] bytes, String extension) { this.bytes=bytes; this.extension=extension; }
    byte[] getBytes() { return bytes; }
    String getFilename() { return "security." + extension; }
    String getContentType() { return "jpg".equals(extension) ? "image/jpeg" : "image/png"; }

    static WxImageSecurityCopy prepare(MultipartFile file) {
        try (InputStream source=file.getInputStream(); ImageInputStream input=ImageIO.createImageInputStream(source)) {
            if(input==null) throw unavailable();
            Iterator<ImageReader> readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext()) throw unavailable();
            ImageReader reader=readers.next();
            try {
                reader.setInput(input,false,false);
                String format=reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if(!("png".equals(format)||"jpeg".equals(format)||"jpg".equals(format)||"gif".equals(format))) throw unavailable();
                int frames=reader.getNumImages(true);
                if(frames!=1) throw new UploadSecurityUnavailableException("动态图安全检查暂不支持，请改为静态图重试");
                int width=reader.getWidth(0),height=reader.getHeight(0);
                // Reuse the already installed upload policy's decode budget before allocating pixels.
                if(width<=0||height<=0||(long)width*height>40_000_000L) throw unavailable();
                final boolean[] warning={false};
                reader.addIIOReadWarningListener((ignored,text)->warning[0]=true);
                BufferedImage original=reader.read(0);
                if(original==null||warning[0]) throw unavailable();
                // Preserve transparency in PNG instead of flattening invisible text onto one background.
                String extension=original.getColorModel().hasAlpha()?"png":"jpg";
                double scale=Math.min(1d,Math.min((double)MAX_WIDTH/width,(double)MAX_HEIGHT/height));
                for(int attempt=0;attempt<12;attempt++) {
                    int targetWidth=Math.max(1,(int)Math.floor(width*scale));
                    int targetHeight=Math.max(1,(int)Math.floor(height*scale));
                    BufferedImage image=new BufferedImage(targetWidth,targetHeight,
                            "png".equals(extension)?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics=image.createGraphics();
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        graphics.drawImage(original,0,0,targetWidth,targetHeight,null);
                    } finally {graphics.dispose();}
                    byte[] encoded=encode(image,extension);
                    if(encoded.length>0&&encoded.length<=MAX_BYTES) return new WxImageSecurityCopy(encoded,extension);
                    scale*=0.8d;
                }
                throw unavailable();
            } finally {reader.dispose();}
        } catch(UploadSecurityUnavailableException failure) {throw failure;}
        catch(Exception failure) {throw new UploadSecurityUnavailableException("安全检查图片处理失败，请改用静态图重试",failure);}
    }

    private static byte[] encode(BufferedImage image,String extension) throws Exception {
        Iterator<ImageWriter> writers=ImageIO.getImageWritersByFormatName(extension);
        if(!writers.hasNext()) throw unavailable();
        ImageWriter writer=writers.next(); ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ImageOutputStream output=ImageIO.createImageOutputStream(bytes)) {
            if(output==null) throw unavailable();
            writer.setOutput(output); ImageWriteParam params=writer.getDefaultWriteParam();
            if("jpg".equals(extension)&&params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); params.setCompressionQuality(0.90f);
            }
            writer.write(null,new IIOImage(image,null,null),params);
            output.flush(); return bytes.toByteArray();
        } finally {writer.dispose();}
    }

    private static UploadSecurityUnavailableException unavailable() {
        return new UploadSecurityUnavailableException("安全检查图片处理失败，请改用静态图重试");
    }
}
