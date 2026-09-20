# Job types

The tablet writes a `jobs` row (`type`, `payload_json`); the server runs the handler and fills `result_json`. Each builder adds rows for the types it owns.

| type | handler owner | creator | payload | result |
|---|---|---|---|---|
| `ocr_page` | V1a `features/library/ocr.py` | V1a tablet (Reader "Read with AI"), and the server itself for scanned PDFs / photos without text | `{document_id, page, only_this_page?, force?, language?}` | `{document_id, page, chars, pages_done, remaining}` |
