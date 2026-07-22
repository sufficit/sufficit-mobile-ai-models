# Sufficit Mobile AI — Android app

App Android que transforma o aparelho num **AI provider** do `sufficit-ai`:
roda um modelo local (embeddings, hoje Qwen3-Embedding-4B) e se anuncia pro
gateway `ai.sufficit.com.br` (haproxy, HA entre eveo-ai/apoint-ai/castrum-ai)
através de uma tailnet dedicada, sem expor nada na internet pública.

Arquitetura completa: [`docs/PLAN-202607091200-mobile-device-ai-provider.md`](../../sufficit-ai/docs/PLAN-202607091200-mobile-device-ai-provider.md)
no repo `sufficit-ai`.

## Status

Implementado:

- ✅ **tsnet embarcado** (`tsgo`, tsnet-in-gomobile) — o app junta a tailnet
  da Sufficit sozinho, sem depender do app oficial do Tailscale; ver
  `TailscaleManager.kt`/`android-tsgo`.
- ✅ **Inferência nativa in-process** — embeddings (llama.cpp) e transcrição
  (whisper.cpp) rodam via cgo direto no runtime Go do `tsgo` (`android-tsgo/
  embedding.go`, `transcription.go`), não mais como subprocessos separados
  (`LlamaServerManager`/`WhisperServerManager`, removidos). `tsgo` já é o
  processo que serve a API externa pela tailnet — um bind direto evita o
  spawn/HTTP-hop redundante de um processo filho.
- ✅ **Model manager** (Fase 6) — busca no Hugging Face, download
  resumível, troca de modelo ativo, smoke-test, remoção (com confirmação).
- ✅ **Heartbeat em foreground service com política de bateria**
  (`SyncForegroundService`) — intervalo curto carregando, esparso na
  bateria; único dono do `performSync`/tsnet (PLAN T1.1).
- ✅ **Boot receiver** (`BootCompletedReceiver`) — retoma o serviço após
  reboot.
- ✅ **Proteção térmica** (PLAN T3.2) — pausa a inferência acima de
  `THERMAL_STATUS_SEVERE`, religa sozinho quando esfria.
- ✅ **Keep-alive por saúde** (PLAN T3.1) — não só "processo vivo": checa
  `/health` e recarrega o modelo depois de 3 falhas seguidas.
- ✅ **Transcrição (Whisper)** — whisper.cpp embarcado via cgo
  (`android-tsgo/transcription.go`), mesmo padrão in-process do embedding.
  Os dois modelos nunca ficam residentes ao mesmo tempo —
  `ModelRuntimeService` faz exclusão mútua (ativar um engine desativa o
  outro, com um cold-start na troca), hardening real contra RAM baixa no
  Galaxy A51 (3.6GB). Nunca é auto-provisionado (diferente do embedding): só
  ativa quando o usuário baixa um modelo Whisper explicitamente em "Modelos
  de IA".
- ✅ **Fix: cleartext bloqueado para 127.0.0.1** — sem `network_security_config.xml`,
  toda chamada OkHttp do app pro loopback local do próprio `tsgo` (inclusive
  o keep-alive de `/health`) falhava com `UnknownServiceException` desde
  sempre (targetSdk 28+ bloqueia cleartext por padrão) — silenciosamente, o
  catch engolia a exceção. Isso fazia o keep-alive de 3 falhas (PLAN T3.1)
  reiniciar tudo num loop infinito a cada ~90s, e todo smoke-test pela UI
  falhar com "modelo não ficou pronto a tempo" — mesmo com a inferência
  nativa respondendo perfeitamente (confirmado via curl direto, bypassando o
  app). Corrigido com `res/xml/network_security_config.xml` liberando
  cleartext só pra 127.0.0.1/localhost.
- ✅ **Fix: flash attention travava toda transcrição** — build do whisper.cpp
  vem com `flash_attn=1` por padrão; nessa CPU o caminho de flash attention
  trava para sempre em qualquer request de inferência (confirmado: `/health`
  respondia normal, só a inferência nunca retornava). Corrigido com
  `flash_attn = false` em `transcription.go`'s `transcriptionContextParams`.

Pendente:

- ❌ Login OAuth completo verificado com conta real (chega na tela do
  Google; troca de código por token ainda não confirmada ponta a ponta).
- ❌ Catálogo de modelos vindo do backend — hoje `DeviceModelCatalog` é
  hardcoded para o Galaxy A51, sem fetch por `Build.MODEL`/`Build.HARDWARE`.
- ❌ Assinatura/distribuição de release (o `assembleRelease` já sai
  minificado, mas segue sem assinatura de produção).

## Arquitetura de processos

```
processo UI (MainActivity/Compose)
    │  broadcasts (ACTION_SYNC_STATE, ACTION_STATUS_CHANGED, ...)
    ├── :sync (SyncForegroundService)
    │     dono único de performSync/announce, do node tsnet e do runtime Go
    │     (tsgo) que efetivamente roda a inferência in-process via cgo —
    │     embedding.go (llama.cpp) e transcription.go (whisper.cpp)
    └── :modelruntime (ModelRuntimeService)
          dono único do NativeEmbeddingManager + NativeTranscriptionManager
          e do ModelRegistry (PLAN T1.4) — só registra *intenção* (qual
          arquivo devia estar carregado); quem carrega de fato é :sync, via
          broadcast (ACTION_STATUS_CHANGED) — ver kdoc de
          NativeEmbeddingManager
```

A UI nunca chama `performSync`, `TailscaleManager` ou `NativeEmbeddingManager`
diretamente — só envia comandos (`startForegroundService` com uma `action`)
e escuta broadcasts com o resultado. Isso é deliberado: um crash num
processo (download OOM, bug de UI) nunca derruba os outros dois — cada
processo Android só mata a si mesmo. Diferente de antes (subprocessos
separados), um crash de inferência (cgo/llama.cpp ou cgo/whisper.cpp) agora
acontece dentro do próprio processo `:sync` — troca isolamento de crash de
inferência por não ter mais o overhead de spawn/HTTP-hop de um processo
filho (ver kdoc de `ModelRuntimeService`).

## Build local

Requisitos: JDK 17, Android SDK (API 35). NDK só é necessário se for
recompilar os binários nativos (não para um build/install normal — os
`.so` já ficam commitados via Git LFS).

```bash
cd android
echo "sdk.dir=/caminho/para/Android/sdk" > local.properties
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Recompilando os binários nativos

Só necessário ao atualizar llama.cpp/whisper.cpp/tsgo, não para builds do
dia a dia — `tsgo.aar` já sai commitado com tudo linkado.

- `scripts/build-llama-static.sh` — cross-compila llama.cpp como libs
  estáticas (`libllama.a`/`libggml*.a`) pra `android-tsgo/.llama-static/`,
  consumidas pelas diretivas cgo de `embedding.go`.
- `scripts/build-whisper-static.sh` — mesma ideia pro whisper.cpp
  (`android-tsgo/.whisper-static/`), consumidas por `transcription.go`. A
  parte não-óbvia: whisper.cpp vendora seu próprio fork do ggml, incompatível
  com o do llama.cpp nessas tags — linkar as duas cópias estáticas no mesmo
  binário dá erro de símbolo duplicado. O script contorna isso renomeando
  (`objcopy --redefine-syms`) todo símbolo ggml/gguf que o build do whisper
  exporta com prefixo `wsp_` antes de gerar os `.a` finais — ver o cabeçalho
  do próprio script pra motivação completa.
- `scripts/build-tsgo-aar.sh` — roda os dois scripts acima automaticamente
  se `.llama-static`/`.whisper-static` ainda não existirem, depois
  `gomobile bind` de `android-tsgo/` pra gerar o `tsgo.aar` final. Rodar
  isso depois de qualquer mudança em `tsgo.go`/`embedding.go`/
  `transcription.go`.

Todos os scripts esperam `ndk;27.2.12479018` instalado
(`sdkmanager --install "ndk;27.2.12479018"`) — mesma versão usada nos
binários já commitados, pra manter o toolchain consistente.

## Publicando na Play Store

Decisão de distribuição: **produção pública, sem restrição de listagem.** A trava de
segurança real não é o APK — é o backend (pareamento por token ou OAuth). Alguém que baixa o
app sem ser cliente Sufficit só vê a tela de login; sem credencial válida não entra na
tailnet nem faz nada. Então listar publicamente não expõe a tailnet a mais risco.

### Assinatura (feito)

- Upload key gerada em `android/keystore/upload-keystore.jks` (gitignored — nunca commitada).
  Senha e alias em `android/keystore.properties` (também gitignored).
- Com **Play App Signing** (padrão hoje em dia), essa é só a *upload key* — o Google guarda a
  chave de assinatura real do app. Se a upload key se perder, dá pra pedir reset via suporte
  do Play Console (não é fatal como era antigamente).
- `signingConfigs.release` em `app/build.gradle.kts` lê `keystore.properties` se o arquivo
  existir; se não existir (CI, outra máquina), o release builda sem assinatura — não quebra.
- `./gradlew :app:bundleRelease` gera o `.aab` assinado em
  `app/build/outputs/bundle/release/app-release.aab` — é esse arquivo que sobe no Play
  Console (não o `.apk`).
- **Backup**: guarde `android/keystore/upload-keystore.jks` e a senha em
  `android/keystore.properties` num cofre de senhas da empresa. Sem isso, uma máquina nova
  não consegue gerar builds assinados compatíveis (mesmo com o reset de upload key, é mais
  trabalho).

### O que ainda é manual (fora deste repo)

1. Conta de desenvolvedor no Google Play Console (Google Workspace/conta da Sufficit + taxa
   única de US$25 + verificação de identidade).
2. Criar o app no Console com `applicationId = com.sufficit.ai.mobiledevice` (definitivo,
   não muda depois de publicado).
3. Ativar Play App Signing e fazer upload da primeira `.aab` assinada com a upload key acima.
4. Preencher: Data Safety (que dados o app coleta/compartilha — token de pareamento, device
   info via `Build.MODEL`; nenhum dado de terceiros), questionário de classificação de
   conteúdo, política de privacidade (URL pública — ainda não existe, precisa hospedar em
   algum lugar da Sufficit).
5. Declarar o uso de `FOREGROUND_SERVICE_SPECIAL_USE` no Console ("App content" →
   "Foreground service permission") — justificar como "provedor de inferência de IA
   always-on conectado a uma tailnet privada da empresa", já documentado no manifest
   (`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`). Esse tipo de permissão passa por review manual do
   Google — pode pedir esclarecimento ou rejeitar na primeira submissão.
6. Ícone/screenshots/texto da loja — pendente (ver PLAN Fase 6, item 4: ícone ainda usa a
   espiral vermelha antiga, não a marca laranja atual).

## Download direto (fora da Play Store)

Repo é público — distribuição direta do `.apk` em dois lugares, mesmo build assinado:

- **GitHub Releases** (recomendado, mais descobrível): https://github.com/sufficit/sufficit-mobile-ai-models/releases/latest
- Bucket GCS (mantido por compatibilidade com links já distribuídos):
  - **Sempre a versão mais recente**: https://storage.googleapis.com/suff-public/sufficit-mobile-ai-models/sufficit-mobile-ai-models-latest.apk
  - Histórico por versão: `gs://suff-public/sufficit-mobile-ai-models/releases/sufficit-mobile-ai-models-v<versionName>-<versionCode>.apk`

`.github/workflows/release.yml` builda e publica automaticamente a cada push em `main` que
toca `android/**` ou `android-tsgo/**` (ou via `workflow_dispatch` manual). Usa a mesma upload
key de `## Assinatura` acima — release assinado, não debug. Tag da release é
`v<versionName>-<versionCode>` — rodar de novo sem bump de versão atualiza a release existente
em vez de falhar em tag duplicada. Requer estes secrets no repo (`gh secret list`):

- `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
  `ANDROID_KEY_PASSWORD` — já configurados (mesma upload key local).
- `GCP_SA_KEY` — chave JSON de service account do projeto `voip-184717` com permissão de
  escrita no bucket `suff-public` (`Storage Object Admin` ou equivalente no path
  `sufficit-mobile-ai-models/*`) — já configurado.
- GitHub Release usa `GITHUB_TOKEN` automático do workflow (`permissions: contents: write`),
  não precisa de secret extra.

**IPA (iOS)**: não gerado ainda — não existe projeto iOS neste repo (fase 2, não iniciada).
Precisa do port iOS (`gomobile bind -target=ios` + shell Swift), runner macOS no workflow, e
certificado/provisioning da Apple (assinatura é obrigatória mesmo para distribuição ad-hoc).

## Fluxo de pareamento (hoje)

Gateway é fixo (`Config.kt`) — só aparecem dois campos na tela: "Tailnet
address" (temporário, manual) e o pairing token (Modo A).

**Modo A:**
1. No `sufficit-ai` web, ir em `/ai/mobile-devices`, criar um pairing token
   pra um contexto/tenant.
2. Abrir o app, preencher "Tailnet address" (endereço onde o `llama-server`
   local escuta — ainda manual) e colar o pairing token.
3. "Parear com token" → chama `announce` (sem lista de modelos) → backend
   descobre os modelos via `/v1/models` → aparece como provider
   (`Type=openai`) no `sufficit-ai`, selecionável em preset.

**Modo B:**
1. Preencher "Tailnet address".
2. "Entrar com Sufficit (Google)" → abre o browser, login Google (cria
   conta Sufficit automaticamente se não existir) → volta pro app via
   `sufficitmobileaimodels://callback`.
3. App troca o code por tokens, chama `self-announce` (mesmo discovery
   automático) com o Bearer token → aparece como provider, sem token pra
   copiar/colar.

## Não fazer (ver PLAN)

- Não pedir pro usuário instalar o app oficial do Tailscale — o backend vai
  ser embarcado no APK (Fase 4).
- Não guardar o `client_secret` no app — o client OAuth é público
  (`RequireClientSecret=false`), PKCE é a proteção contra interceptação do
  authorization code, não um secret embutido no APK.
- Não expor a Gateway URL como campo de tela — é detalhe de implementação
  (`Config.DEFAULT_GATEWAY_URL`), não algo que o usuário final deveria ver
  ou precisar saber.
- Não mandar lista de modelos no `announce` — deixa o backend descobrir via
  `/v1/models`, igual qualquer outro provider OpenAI-compatible.

## Superfície local

O `llama-server` embarcado escuta em `127.0.0.1:8090` **sem autenticação** —
qualquer app instalado no aparelho pode chamar `/v1/embeddings` local e gerar
embeddings de graça. Aceito por ora: o dado exposto não é sensível (não há
prompts/documentos do usuário armazenados ali, só o serviço de inferência) e
o pior caso é consumo de CPU por um app malicioso. Se algum dia este app
passar a servir modelos de geração (não só embeddings), ligar `--api-key` no
`llama-server` com uma chave por device vinda do `announce` — isso exige
mudança coordenada no backend (`sufficit-ai`), não implementado agora.
