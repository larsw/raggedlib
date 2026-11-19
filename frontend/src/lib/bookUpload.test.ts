import { describe, expect, test } from "bun:test";
import { validateBookUploadFiles } from "./bookUpload";

describe("book upload validation", () => {
  test("accepts multiple Calibre formats with one basename", () => {
    expect(validateBookUploadFiles([
      new File(["ebook"], "Foo.epub"),
      new File(["pdf"], "foo.PDF"),
    ])).toBeNull();
  });

  test("rejects unsupported extensions and duplicate formats in a basename group", () => {
    expect(validateBookUploadFiles([new File(["script"], "book.exe")])).toContain("not a supported");
    expect(validateBookUploadFiles([
      new File(["one"], "Foo.epub"),
      new File(["two"], "foo.EPUB"),
    ])).toContain("Only one EPUB");
  });
});
