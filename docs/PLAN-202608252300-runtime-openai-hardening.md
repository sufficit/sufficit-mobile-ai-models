# Plano concluído — runtime OpenAI e confiabilidade móvel

Data: 2026-08-26  
Status: implementado e validado em Galaxy A51 (`SM-A515F`)

## Objetivo

Fazer o dispositivo anunciar apenas capacidades que consegue executar naquele
instante, manter o contrato OpenAI previsível e impedir que concorrência, troca
de engine ou artefato nativo desatualizado causem falhas silenciosas.

## Entregas

- Descoberta baseada no modelo residente, não em todos os downloads.
- Validação obrigatória de `model` antes da inferência e novamente após a
  admissão, protegendo contra troca concorrente de engine.
- Estados `idle`, `loading`, `ready`, `busy` e `failed` visíveis em `/health`.
- Uma única inferência admitida por engine, sem fila pendente; concorrência
  retorna `429` e `Retry-After: 2`.
- Embeddings com entrada individual ou em lote, índices, contabilização de
  tokens e `encoding_format=float`.
- `dimensions` condicionado à capacidade MRL do modelo. Qwen3-Embedding-0.6B
  permite `32..1024`; gte-Qwen2 aceita somente sua dimensão nativa de 1536.
- Transcrição limitada a 64 MiB de áudio/65 MiB multipart, com limpeza dos
  temporários e rejeição explícita do modelo incorreto.
- Servidor HTTP com limite de cabeçalhos e timeouts de cabeçalho/idle, sem um
  `WriteTimeout` que interromperia Whisper legítimo de longa duração.
- Timeout de 20 minutos nos providers móveis e teto específico de 30 minutos
  na transcrição OpenAI; demais operações mantêm o limite existente.
- Exclusão mútua real também no processo `:sync`: descarrega o engine oposto
  antes de carregar o novo e recusa um broadcast inválido que peça os dois.
- Mudanças de modelo recebidas por broadcast são copiadas e processadas fora
  da main thread, sob mutex, para serializar unload/load e evitar ANR ou duas
  transições nativas concorrentes.
- CI com `go test -race`, `go vet` e manifesto de integridade do AAR.

## Evidências em dispositivo

- Embedding gte-Qwen2: vetor 1536, `usage.prompt_tokens=12`, modelo correto.
- Lote: 10 vetores e 76 tokens; chamada concorrente recebeu `429` com
  `Retry-After: 2` e não ficou enfileirada.
- Dimensão reduzida em gte-Qwen2: `400 unsupported_parameter`, conforme a
  ausência de MRL; modelo divergente: `409 model_not_loaded`.
- Troca Large Whisper -> gte-Qwen2 inicialmente reproduziu OOM: `lmkd` matou
  `:sync` com aproximadamente 1,44 GiB RSS e 318 MiB swap. Após corrigir a
  ordem de unload/load, as trocas Large -> gte -> Tiny -> Large concluíram sem
  reinício do processo.
- Whisper Tiny transcreveu WAV português de 6,21 s em 386,98 s. A precisão foi
  baixa, confirmando que Tiny serve apenas a smoke test; o Large v3 Turbo foi
  restaurado e terminou em `ready`.

## Próximas evoluções independentes

- Catálogo remoto de modelos por perfil de hardware, substituindo o snapshot
  específico do Galaxy A51.
- Instalar Qwen3-Embedding-0.6B no dispositivo de referência e manter um smoke
  live opcional de redução `1024 -> 512`; a regressão portátil já cobre o
  algoritmo e os limites `32..1024`.
- Métricas persistentes de latência, temperatura, memória e erro por modelo
  para alimentar recomendações do catálogo remoto.
- Autenticação de aplicação dentro da tailnet requer protocolo coordenado com
  o backend; não deve ser improvisada no listener e quebrar dispositivos
  pareados existentes.
