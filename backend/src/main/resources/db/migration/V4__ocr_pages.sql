-- Pages of the indexed text that came from OCR (PDF pages without a text layer); 0 = none.
ALTER TABLE books ADD COLUMN ocr_pages integer NOT NULL DEFAULT 0;
