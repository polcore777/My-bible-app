package com.ryan.biblestudy.core;

import java.io.IOException;
import java.util.Map;

/**
 * Receives converted module content. The app stores it in its own SQLite format:
 *   info(key, value)
 *   verses(book, chapter, verse, html, plain)          -- Bibles
 *   comments(book, ch1, v1, ch2, v2, html)              -- commentaries / study notes
 *   entries(key, html, plain)                           -- dictionaries / lexicons
 *   sections(id, title, html, plain)                    -- books, topics, devotionals
 *   images(id, title, mime, data)                       -- maps / pictures
 */
public interface ModuleSink {
    String BIBLE = "bible", COMMENTARY = "commentary", DICTIONARY = "dictionary", BOOK = "book", IMAGES = "images";

    void begin(String type) throws IOException;

    void verse(int book, int chapter, int verse, String html, String plain) throws IOException;

    void comment(int book, int ch1, int v1, int ch2, int v2, String html) throws IOException;

    void entry(String key, String html, String plain) throws IOException;

    void section(int order, String title, String html, String plain) throws IOException;

    void image(int order, String title, String mime, byte[] data) throws IOException;

    /** Called last, with the module's details (title, abbreviation, type, strongs ...). */
    void end(Map<String, String> info) throws IOException;
}
