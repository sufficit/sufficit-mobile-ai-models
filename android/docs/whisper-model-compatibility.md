# Whisper no Galaxy A51: compatibilidade, qualidade e áudio telefônico

Este documento registra a matriz executada no aparelho de referência
**Samsung Galaxy A51 (SM-A515F, Exynos 9611, 4 GB, Android 13)** em 25 de
agosto de 2026. O objetivo não é conversação em tempo real: são transcrições
assíncronas de ligações e documentos de áudio, nas quais qualidade e
estabilidade importam mais que latência.

## Decisão

- **Large v3 Turbo Q5_0:** perfil de qualidade alta. É o padrão preferível
  para texto geral quando sete a oito minutos por trecho curto são aceitáveis.
- **Medium Q5_0:** perfil de conferência de números. Foi o melhor em manter
  separada a sequência falada `8 7 3 5`, com custo de aproximadamente dez
  minutos no corpus.
- **Small Q8_0:** melhor equilíbrio de armazenamento, RAM e qualidade. É o
  perfil indicado para aparelhos ocupados por outros serviços.
- **Tiny/Base:** compatíveis, mas restritos a smoke tests ou situações de
  memória extrema. A precisão em português telefônico foi insuficiente.
- **Small completo:** compatível, mas não recomendado. Produziu exatamente a
  mesma saída do Small Q8 e consumiu quase o dobro da memória residente.

Todos os modelos selecionados são **multilíngues**. Variantes com sufixo
`.en` não entram no catálogo porque não atendem português.

## Corpus e método

Texto de referência:

> Teste de ligação: a fatura quarenta e dois vence sexta-feira. Protocolo oito
> sete três cinco.

O áudio controlado tem 7,604875 s, voz sintética em português e foi convertido
para WAV mono de 8 kHz. Cada modelo recebeu exatamente o mesmo PCM 16-bit por
`POST /v1/audio/transcriptions`, com `language=pt`, quatro threads, CPU-only e
sem flash attention. As execuções foram sequenciais, pois o runtime mantém uma
única inferência/modelo residente por vez.

WER/CER são distâncias literais. Elas penalizam equivalências úteis como
`quarenta e dois` → `42` e `oito sete três cinco` → `8735`; por isso a coluna
de observação semântica também faz parte da avaliação.

| Modelo | Arquivo oficial | Tempo | WER | CER | Memória observada | Resultado útil |
|---|---:|---:|---:|---:|---:|---|
| Tiny | 75 MiB | 218,8 s | 118,8% | 52,2% | ~337 MB RSS durante inferência | Não preservou frase nem números |
| Base | 142 MiB | 387,8 s | 100,0% | 46,7% | não isolada | Não trouxe ganho sobre Tiny |
| Small Q8_0 | 252 MiB | 289,4 s | 75,0% | 37,8% | ~498 MB RSS após carga | Recuperou estrutura, ligação, fatura e parte dos números |
| Small completo | 466 MiB | 304,1 s | 75,0% | 37,8% | ~984 MB RSS após carga | Saída idêntica ao Q8_0; não compensa |
| Medium Q5_0 | 514 MiB | 613,5 s | 62,5% | 41,1% | ~945 MB RSS após carga | `Teste de ligação`, `fatura 42` e `8 7 3 5` corretos |
| Large v3 Turbo Q5_0 | 547 MiB | 475,1 s | 62,5% | 46,7% | ~672 MB RSS / ~796 MB PSS após carga | Reconheceu `fatura 42`, `sexta-feira` e `8735` |

Os números de memória são aproximações de `dumpsys meminfo` em execuções
sucessivas. Allocator, cache e swap do Android tornam RSS/PSS comparáveis apenas
como ordem de grandeza, não como requisito mínimo absoluto. Nenhum modelo causou
OOM ou reinício do processo. Ao fim da matriz, o status térmico era `0` e a
temperatura do AP era 28,3 °C.

## Formatos de entrada

O contrato do adaptador é deliberadamente menor que o conjunto de formatos que
algumas APIs em nuvem aceitam. O contêiner obrigatório é **RIFF/WAVE**:

| Formato WAV | Profundidade | Taxa/canais | Estado |
|---|---:|---|---|
| PCM inteiro | 8/16/24/32 bits | qualquer; conversão interna | testes unitários e PCM 8 kHz live |
| IEEE float | 32 bits | qualquer; conversão interna | suportado pelo decoder |
| G.711 μ-law | 8 bits | tipicamente mono 8 kHz | unitário + API live, HTTP 200 |
| G.711 A-law | 8 bits | tipicamente mono 8 kHz | vetores G.711 unitários |
| WAVE_FORMAT_EXTENSIBLE | conforme subformato acima | qualquer | suportado pelo decoder |

O teste live de μ-law com Medium Q5_0 levou 575,7 s e preservou `fatura 42` e
`8 7 3 5`, resultado equivalente ao PCM. A redução de tamanho do arquivo não
reduz o custo de inferência: após decodificação, todos viram float mono de 16
kHz para o whisper.cpp.

MP3, OGG, FLAC, M4A e PCM cru não são aceitos. O chamador deve encapsular ou
transcodificar antes da requisição. Isso é explícito para evitar um fallback
silencioso ou interpretação ambígua de bytes.

## Operação assíncrona

- A API HTTP mantém a conexão até terminar; use worker/fila em segundo plano e
  timeout compatível com o modelo. O IPC Android já responde por callback.
- Há uma inferência por vez. Durante carga ou inferência concorrente, o endpoint
  pode responder 503; o chamador deve reagendar com backoff, sem trocar de modelo.
- Mantenha o `language=pt` quando a origem for conhecida. Autodetecção adiciona
  incerteza sem benefício nesse caso.
- Para ligações longas, segmente por atividade de voz antes do envio. O VAD
  reduz silêncio processado; não altera a responsabilidade do modelo nem deve
  reescrever o áudio falado.
- Não selecione modelo apenas pelo tamanho do arquivo. Small completo demonstrou
  que pesos maiores podem não melhorar a saída; use os perfis medidos.

## Fontes oficiais

- [whisper.cpp — README, plataformas, uso, VAD e memória](https://github.com/ggml-org/whisper.cpp/blob/master/README.md)
- [whisper.cpp — modelos convertidos, tamanhos e SHA-1](https://huggingface.co/ggerganov/whisper.cpp/blob/main/README.md)
- [OpenAI Whisper — model card e famílias multilíngues](https://github.com/openai/whisper/blob/main/model-card.md)

Os arquivos usados foram baixados do repositório oficial `ggerganov/whisper.cpp`.
O Large v3 Turbo Q5_0 foi validado com SHA-1
`e050f7970618a659205450ad97eb95a18d69c9ee`; Medium Q5_0 com
`7718d4c1ec62ca96998f058114db98236937490e` e Small Q8_0 com
`bcad8a2083f4e53d648d586b7dbc0cd673d8afad`.
