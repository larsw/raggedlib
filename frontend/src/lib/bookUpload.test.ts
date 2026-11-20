import { describe, expect, test } from "bun:test";
import type { UploadOptions } from "../api/types";
import { formatOf, uploadAccept, validateBookUploadFiles, validateFormatFile } from "./bookUpload";

const options: UploadOptions = {
  formats: ["EPUB", "PDF", "TXT"],
  maxFormatSize: 10,
  maxFormatSizeLabel: "10 bytes",
  maxRequestSize: 15,
  maxRequestSizeLabel: "15 bytes",
};

describe("book upload validation", () => {
  test("accepts multiple Calibre formats with one basename", () => {
    expect(validateBookUploadFiles([new File(["ebook"], "Foo.epub"), new File(["pdf"], "foo.PDF")], options)).toBeNull();
  });

  test("rejects unsupported extensions and duplicate formats in a basename group", () => {
    expect(validateBookUploadFiles([new File(["script"], "book.exe")], options)).toContain("not a supported");
    expect(validateBookUploadFiles([new File(["x"], "noextension")], null)).toContain("not a supported");
    expect(validateBookUploadFiles([new File(["one"], "Foo.epub"), new File(["two"], "foo.EPUB")], options))
      .toContain("Only one EPUB");
  });

  test("names the file over the per-file limit, and refuses uploads over the request limit", () => {
    expect(validateBookUploadFiles([new File(["12345678901"], "Big.pdf")], options))
      .toBe("Big.pdf is larger than the 10 bytes limit for one file.");
    expect(validateBookUploadFiles([new File(["12345678"], "A.epub"), new File(["12345678"], "B.epub")], options))
      .toContain("more than the 15 bytes limit for one upload");
  });

  test("without the options only names are checked; the server checks the rest", () => {
    expect(validateBookUploadFiles([new File(["12345678901"], "Big.pdf")], null)).toBeNull();
    expect(validateFormatFile(new File(["x"], "Comic.cbz"), null)).toBeNull();
    expect(validateFormatFile(new File(["x"], "Comic.cbz"), options)).toContain("not a supported");
  });

  test("formats come from file names, and the picker accepts the backend's formats", () => {
    expect(formatOf("C:\\books\\Novel.Epub")).toBe("EPUB");
    expect(formatOf(".hidden")).toBe("");
    expect(uploadAccept(options)).toBe(".epub,.pdf,.txt");
    expect(uploadAccept(null)).toBeUndefined();
  });
});
