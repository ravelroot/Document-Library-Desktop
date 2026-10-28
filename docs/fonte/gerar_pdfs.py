"""Gera os manuais em PDF do Lume para Windows a partir dos HTML desta pasta.

Uso:  python docs/fonte/gerar_pdfs.py            # os dois
      python docs/fonte/gerar_pdfs.py manual     # só o manual
      python docs/fonte/gerar_pdfs.py banco      # só o banco de dados

Requer: pip install weasyprint==70.0
Grava o PDF na raiz do repositório e uma cópia idêntica em docs/.
Os prints vêm de docs/images (gerados por `npm --prefix e2e test -- --prints`); um print que ainda
não exista é trocado por um quadro vazio, para o PDF sair mesmo assim.
"""
import logging
import shutil
import sys
from pathlib import Path

from weasyprint import HTML

AQUI = Path(__file__).resolve().parent
RAIZ = AQUI.parents[1]
DOCS = {
    "manual": ("manual.html", "Manual-Lume-Desktop.pdf"),
    "banco": ("banco-de-dados.html", "Banco-de-Dados-Lume-Desktop.pdf"),
}


class _SemImagem(logging.Filter):
    """Imagem ausente vira aviso curto em vez de uma pilha de erros."""

    def filter(self, record):
        msg = record.getMessage()
        if "Failed to load image" in msg or "No such file" in msg:
            print(f"  aviso: imagem ausente ({msg.split(':', 1)[-1].strip()[:90]})")
            return False
        return True


logging.getLogger("weasyprint").addFilter(_SemImagem())


def gerar(chave: str) -> Path:
    fonte, saida = DOCS[chave]
    destino = RAIZ / saida
    documento = HTML(filename=str(AQUI / fonte), base_url=str(AQUI)).render()
    documento.write_pdf(str(destino), optimize_images=True, jpeg_quality=85, dpi=170)
    shutil.copyfile(destino, RAIZ / "docs" / saida)
    print(f"{saida}: {len(documento.pages)} páginas · {destino.stat().st_size / 1024:.0f} KB")
    return destino


if __name__ == "__main__":
    for chave in (sys.argv[1:] or list(DOCS)):
        gerar(chave)
