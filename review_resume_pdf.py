import pdfplumber
from pathlib import Path

SOURCE = Path(r"C:\Users\我爱cb\Desktop\李飞洋_Java后端开发实习生简历.pdf")

with pdfplumber.open(SOURCE) as pdf:
    print(f"pages={len(pdf.pages)}")
    for i, page in enumerate(pdf.pages, 1):
        print(f"---PAGE {i}---")
        print(page.extract_text() or "")
        page.to_image(resolution=150).save(Path(r"S:\videonest\tmp\pdf_review") / f"page-{i}.png")
