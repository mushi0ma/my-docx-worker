package com.example.document_parser.config;

import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.GenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;

public class MappingJackson3HttpMessageConverter implements GenericHttpMessageConverter<Object> {

    private final ObjectMapper objectMapper;
    private final List<MediaType> supportedMediaTypes = Arrays.asList(
            MediaType.APPLICATION_JSON,
            new MediaType("application", "*+json"));

    public MappingJackson3HttpMessageConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean canRead(Type type, Class<?> contextClass, MediaType mediaType) {
        return canRead(mediaType);
    }

    @Override
    public Object read(Type type, Class<?> contextClass, HttpInputMessage inputMessage)
            throws IOException, HttpMessageNotReadableException {
        JavaType javaType = getJavaType(type, contextClass);
        try {
            return objectMapper.readValue(inputMessage.getBody(), javaType);
        } catch (Exception ex) {
            throw new HttpMessageNotReadableException("Could not read JSON: " + ex.getMessage(), ex, inputMessage);
        }
    }

    @Override
    public boolean canWrite(Type type, Class<?> clazz, MediaType mediaType) {
        return canWrite(mediaType);
    }

    @Override
    public void write(Object o, Type type, MediaType contentType, HttpOutputMessage outputMessage)
            throws IOException, HttpMessageNotWritableException {
        try {
            outputMessage.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            objectMapper.writeValue(outputMessage.getBody(), o);
        } catch (Exception ex) {
            throw new HttpMessageNotWritableException("Could not write JSON: " + ex.getMessage(), ex);
        }
    }

    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
        return canRead(mediaType);
    }

    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return canWrite(mediaType);
    }

    @Override
    public List<MediaType> getSupportedMediaTypes() {
        return supportedMediaTypes;
    }

    @Override
    public Object read(Class<?> clazz, HttpInputMessage inputMessage)
            throws IOException, HttpMessageNotReadableException {
        try {
            return objectMapper.readValue(inputMessage.getBody(), clazz);
        } catch (Exception ex) {
            throw new HttpMessageNotReadableException("Could not read JSON: " + ex.getMessage(), ex, inputMessage);
        }
    }

    @Override
    public void write(Object o, MediaType contentType, HttpOutputMessage outputMessage)
            throws IOException, HttpMessageNotWritableException {
        try {
            outputMessage.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            objectMapper.writeValue(outputMessage.getBody(), o);
        } catch (Exception ex) {
            throw new HttpMessageNotWritableException("Could not write JSON: " + ex.getMessage(), ex);
        }
    }

    private boolean canRead(MediaType mediaType) {
        if (mediaType == null)
            return true;
        for (MediaType supported : getSupportedMediaTypes()) {
            if (supported.includes(mediaType))
                return true;
        }
        return false;
    }

    private boolean canWrite(MediaType mediaType) {
        if (mediaType == null || MediaType.ALL.equalsTypeAndSubtype(mediaType))
            return true;
        for (MediaType supported : getSupportedMediaTypes()) {
            if (supported.isCompatibleWith(mediaType))
                return true;
        }
        return false;
    }

    private JavaType getJavaType(Type type, Class<?> contextClass) {
        return objectMapper.constructType(type);
    }
}
