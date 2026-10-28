# Como contribuir com o Lume

Obrigado pelo interesse em ajudar! Este guia resume como preparar o ambiente, propor mudanças e registrar o trabalho feito em dupla.

## Antes de começar

- Procure nas [issues](https://github.com/ravelroot/Document-Library-Desktop/issues) se o assunto já está sendo discutido.
- Para mudanças grandes, abra uma issue primeiro e descreva a ideia.

## Ambiente

Requisitos: JDK 21, Maven 3.9 e Git. O Node.js 22+ só é necessário para o teste de interface, e o NSIS só para gerar o `Lume.exe`.

```bash
git clone https://github.com/ravelroot/Document-Library-Desktop.git
cd Document-Library-Desktop
mvn test                 # testes JUnit
mvn spring-boot:run      # abre o Lume a partir do código
```

Os comandos completos estão na seção [Desenvolvimento](README.md#desenvolvimento) do README.

## Fluxo de trabalho

1. Crie uma branch a partir da `main` com um nome curto e descritivo (`corrige-leitor-docx`, `estatisticas-por-ano`).
2. Faça commits pequenos, cada um com uma única mudança.
3. Rode `mvn test` antes de enviar. Se mexer na interface, rode também `npm --prefix e2e test`.
4. Abra o pull request para a `main` e preencha o template.

O CI roda os testes JUnit e o teste de interface e gera o `Lume.exe` em cada pull request.

## Mensagens de commit

- Em português, no presente: `Adiciona exportação em BibTeX`, `Corrige capa de DOCX sem título`.
- Primeira linha com até 72 caracteres; se precisar de detalhes, escreva-os depois de uma linha em branco.

## Trabalho em dupla

Quando duas pessoas fizerem a mudança juntas, quem faz o commit adiciona a outra como co-autora no fim da mensagem, depois de uma linha em branco:

```text
Adiciona filtro por idioma

Co-authored-by: Nome da Pessoa <e-mail@exemplo.com>
```

Use o e-mail ligado à conta do GitHub da pessoa (ou o endereço `noreply` que aparece em *Settings → Emails*) para que o commit apareça no perfil dela.

## Estilo de código

O `.editorconfig` define o padrão: UTF-8, fim de linha LF, 4 espaços em Java e 2 em JS, CSS, HTML, JSON, YAML e Markdown.

## Licença

Ao contribuir, você concorda que sua contribuição será distribuída sob a [licença MIT](LICENSE) do projeto.
