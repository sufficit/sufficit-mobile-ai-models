# PLAN — Hardening de arquitetura, UI e build (sufficit-mobile-ai-models)

Data: 2026-07-10
Origem: revisão completa do repositório (app Android, proxy Node POC, camada tsgo, scripts, gradle).
Escopo: **somente este repositório**. Mudanças de backend (sufficit-ai) estão listadas no final como "coordenação", NÃO devem ser feitas aqui.

## Como usar este plano (LEIA PRIMEIRO)

1. Execute as fases **em ordem** (0 → 5). Dentro de cada fase, as tarefas em ordem.
2. **Um commit por tarefa** (mensagem sugerida em cada uma). Nunca acumule várias tarefas num commit.
3. Depois de CADA tarefa que toca o app Android, valide com:
   ```bash
   cd /mnt/sufficit/sufficit-mobile-ai-models/android && ./gradlew :app:assembleDebug
   ```
   Se o build quebrar, conserte antes de seguir. Não pule a validação.
4. Depois de cada tarefa que toca o proxy Node, valide com:
   ```bash
   cd /mnt/sufficit/sufficit-mobile-ai-models && node --check src/server.js && node --check src/adb.js && node --check src/config.js
   ```
5. Tarefas marcadas **[DEVICE]** precisam de um aparelho físico para verificação final. Faça a mudança, garanta que compila, e liste-as no relatório final como "pendente de teste em aparelho". Não tente inventar um teste sem aparelho.
6. **Não faça refactor além do que a tarefa pede.** Não renomeie arquivos, não mova pacotes, não "melhore" código vizinho, não mude texto que não foi mandado mudar.
7. Strings de UI deste app são em **português brasileiro**. Código, comentários e mensagens de commit em inglês.

### O que NÃO fazer (regras existentes do projeto — não viole)

- Não expor a Gateway URL como campo de tela (fica fixa em `Config.DEFAULT_GATEWAY_URL`).
- Não guardar `client_secret` no app (client OAuth é público + PKCE).
- Não mandar lista de modelos no `announce` (backend descobre via `GET /v1/models`).
- Não pedir para o usuário instalar o app oficial do Tailscale.
- Não remover nenhum `.so` de `jniLibs` (o llama-server foi linkado contra todos eles; remover exige rebuild nativo).
- Não mexer no build nativo do llama.cpp nem regenerar `tsgo.aar` — são artefatos prontos, tratados como binários.

---

## Fase 0 — Higiene de repositório (fazer ANTES de qualquer código)

Estado atual: só existem 2 commits; TODO o trabalho das Fases 4-6 (tsgo, LlamaServerManager, ModelRuntimeService, ModelsScreen, catálogo, etc.) está **untracked/modificado sem commit**. Há ~190MB de `.so` + 13.8MB de `.aar` fora do controle de versão. Se nada for feito, um `git clean` destrói o produto.

### T0.1 — Strip dos binários nativos (ANTES do primeiro commit, para não eternizar 190MB no histórico)

Os `.so` em `android/app/src/main/jniLibs/arm64-v8a/` vêm de build próprio e carregam símbolos de debug (`libllama-common.so` tem 73MB). O AGP não faz strip aqui porque o projeto não configura NDK.

Passos:
1. Backup fora do repo:
   ```bash
   mkdir -p ~/backup-jnilibs-20260710
   cp /mnt/sufficit/sufficit-mobile-ai-models/android/app/src/main/jniLibs/arm64-v8a/*.so ~/backup-jnilibs-20260710/
   ```
2. Localize o `llvm-strip` do NDK instalado (procure em `$ANDROID_HOME/ndk/*/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip`). Se não houver NDK instalado, use o `strip` de uma toolchain aarch64 (`aarch64-linux-gnu-strip`) ou instale o NDK via sdkmanager. **Se nenhuma opção existir na máquina, pule esta tarefa inteira e registre no relatório** — não tente strippar com o `strip` x86 do sistema (corrompe).
3. Strip:
   ```bash
   for f in /mnt/sufficit/sufficit-mobile-ai-models/android/app/src/main/jniLibs/arm64-v8a/*.so; do
     llvm-strip --strip-unneeded "$f"
   done
   ls -la /mnt/sufficit/sufficit-mobile-ai-models/android/app/src/main/jniLibs/arm64-v8a/
   ```
4. `./gradlew :app:assembleDebug` deve continuar passando.

**[DEVICE]** Verificação final: instalar o APK e conferir que o llama-server ainda inicia (o binário `libllamaserver.so` de 7KB é só o launcher; os grandes são bibliotecas).

Critério de aceite: soma dos `.so` cai drasticamente (esperado: de ~190MB para algo na casa de dezenas de MB); build passa.

Commit: `build(android): strip debug symbols from prebuilt native libs`

### T0.2 — Git LFS para binários

1. `git lfs install` no repo. Se o comando não existir ou o remote não aceitar LFS, **fallback**: commit direto dos arquivos strippados (só são aceitáveis no histórico porque T0.1 os reduziu) e registre a decisão no relatório.
2. ```bash
   cd /mnt/sufficit/sufficit-mobile-ai-models
   git lfs track "android/app/src/main/jniLibs/**/*.so"
   git lfs track "android/app/libs/*.aar"
   git add .gitattributes
   ```

Commit: `build: track native binaries via git-lfs`

### T0.3 — Commit de baseline de todo o trabalho pendente

1. Confira que `.env` e `android/local.properties` **continuam ignorados** (`git status` não pode listá-los — o `.gitignore` já cobre; apenas confirme).
2. Adicione tudo que está untracked/modificado:
   ```bash
   git add -A
   git status   # revisar: NÃO pode aparecer .env nem local.properties
   git commit -m "feat(android): embedded llama-server, tsnet tailnet join, model manager (Fases 4-6)

   - tsgo: tsnet-in-gomobile userspace tailnet node + port proxy
   - LlamaServerManager: llama-server as native subprocess from jniLibs
   - ModelRuntimeService (own process): model lifecycle, downloads, tests
   - ModelsScreen: HF search, download, switch, smoke-test, delete
   - DeviceModelCatalog: known-good models for Galaxy A51
   - BootCompletedReceiver: resume after reboot"
   ```

Critério de aceite: `git status` limpo (exceto nada); `git log` mostra o baseline.

### T0.4 — Criar `docs/` como casa da documentação do repo

Este arquivo já mora em `docs/`. Ajuste o `README.md` da raiz: na seção que aponta para o PLAN do sufficit-ai, adicione uma linha apontando para `docs/` deste repo ("Improvement plan: docs/PLAN-202607101500-...").

Commit: `docs: reference local docs folder from README`

---

## Fase 1 — Bugs de arquitetura (prioridade máxima)

### T1.1 — Dono único do sync: UI deixa de chamar `performSync` diretamente

**Problema (bug real):** `performSync` roda hoje em DOIS processos:
- Processo UI: botão "Sincronizar agora" em `MainActivity.syncNow()`.
- Processo `:sync`: loop do `SyncForegroundService`.

Consequências:
1. **Dois nós tsnet simultâneos com a mesma identidade.** `TailscaleManager`/`tsgo` é singleton *por processo*. O `isRunning()` do processo UI não enxerga o tsnet do `:sync` e vice-versa → cada processo sobe seu próprio nó com o MESMO stateDir/identidade → flapping de endpoint WireGuard, comportamento errático na tailnet.
2. **Corrida de refresh de token OAuth.** Os dois processos refrescam o access token e gravam em `EncryptedSharedPreferences` (que NÃO é multi-process safe). Se o IdP rotacionar refresh tokens, um processo pode persistir o token antigo → `invalid_grant` → device silenciosamente offline.

**Correção:** todo sync passa a acontecer SOMENTE dentro do `SyncForegroundService`. A UI envia comandos e escuta broadcasts (mesmo padrão já usado com `ModelRuntimeService`).

Arquivos: `SyncForegroundService.kt`, `MainActivity.kt`, `SyncLogic.kt` (sem mudança), `TailscaleManager.kt` (helper novo).

Passos:

1. Em `SyncForegroundService`, adicione ao `companion object`:
   ```kotlin
   const val ACTION_SYNC_NOW = "com.sufficit.ai.mobiledevice.action.SYNC_NOW"
   const val ACTION_LOGOUT = "com.sufficit.ai.mobiledevice.action.LOGOUT"
   const val ACTION_SYNC_STATE = "com.sufficit.ai.mobiledevice.broadcast.SYNC_STATE"
   const val EXTRA_SYNC_SUCCESS = "syncSuccess"
   const val EXTRA_SYNC_MESSAGE = "syncMessage"
   const val EXTRA_TAILNET_IP = "tailnetIp"
   const val EXTRA_LAST_SYNC_AT_MS = "lastSyncAtMs"

   fun syncNow(context: Context) {
       val intent = Intent(context, SyncForegroundService::class.java).setAction(ACTION_SYNC_NOW)
       ContextCompat.startForegroundService(context, intent)
   }

   fun logout(context: Context) {
       val intent = Intent(context, SyncForegroundService::class.java).setAction(ACTION_LOGOUT)
       ContextCompat.startForegroundService(context, intent)
   }
   ```
2. Transforme `store/api/oauth/tailscale` em campos do serviço (inicializados em `onCreate`), não variáveis locais do `onStartCommand`.
3. Extraia o corpo do loop para uma função:
   ```kotlin
   private suspend fun syncOnce() {
       if (!store.isPaired()) return
       val result = performSync(store, api, oauth, tailscale)
       val message = when (result) {
           is AnnounceResult.Success -> "Sincronizado"
           is AnnounceResult.Failure -> "Falha na sincronização: ${result.message}"
       }
       updateNotification(message)
       sendBroadcast(
           Intent(ACTION_SYNC_STATE).setPackage(packageName)
               .putExtra(EXTRA_SYNC_SUCCESS, result is AnnounceResult.Success)
               .putExtra(EXTRA_SYNC_MESSAGE, message)
               .putExtra(EXTRA_TAILNET_IP, tailscale.tailnetIp())
               .putExtra(EXTRA_LAST_SYNC_AT_MS, System.currentTimeMillis())
       )
   }
   ```
4. `onStartCommand` trata as actions:
   ```kotlin
   when (intent?.action) {
       ACTION_SYNC_NOW -> scope.launch { syncOnce() }
       ACTION_LOGOUT -> {
           loopJob?.cancel()
           scope.launch {
               tailscale.stop()
               File(filesDir, "tsgo-state").deleteRecursively()
               stopSelf()
           }
           return START_NOT_STICKY
       }
   }
   ```
   (mantendo o arranque do loop como está para intents sem action).
5. Em `TailscaleManager`, adicione:
   ```kotlin
   /** IPv4 da tailnet do nó atual, ou null se não conectado. */
   fun tailnetIp(): String? = try {
       org.json.JSONObject(status()).optString("tailnetIp").takeIf { it.isNotBlank() }
   } catch (_: Exception) { null }
   ```
6. Em `MainActivity`:
   - **Remova** o campo `tailscale` e a chamada direta a `performSync`. `syncNow()` vira:
     ```kotlin
     fun syncNow() {
         syncLoading = true
         syncStatus = null
         SyncForegroundService.syncNow(context)
     }
     ```
   - Registre um `BroadcastReceiver` (mesmo padrão do `DisposableEffect` já existente para `ModelRuntimeService.ACTION_STATUS_CHANGED`) para `SyncForegroundService.ACTION_SYNC_STATE`, que faz:
     ```kotlin
     syncLoading = false
     syncStatus = intent.getStringExtra(SyncForegroundService.EXTRA_SYNC_MESSAGE)
     networkAddress = intent.getStringExtra(SyncForegroundService.EXTRA_TAILNET_IP)
     ```
     (`networkAddress` vira um `mutableStateOf<String?>` novo, passado ao `HomeScreen` no lugar do `null` hardcoded).
   - Timeout de segurança: junto com o `syncNow()`, agende `scope.launch { delay(45_000); if (syncLoading) { syncLoading = false; syncStatus = "Sem resposta do serviço de sincronização." } }`.
   - No logout do `SettingsScreen`, troque `SyncForegroundService.stop(context)` por:
     ```kotlin
     SyncForegroundService.logout(context)
     ModelRuntimeService.stop(context)
     store.clear()
     ```
7. `onDestroy` do `SyncForegroundService`: adicione `tailscale.stop()` antes de `super.onDestroy()` (nó tsnet nunca deve sobreviver sem o serviço).
8. `PairingApi` continua sendo usado pela `MainActivity`? Depois desta tarefa, **não** — remova o campo `api` da `MainActivity` e o parâmetro de `SufficitApp` se ficar sem uso. `OAuthManager` fica (login/exchange/userinfo são UI).

Critério de aceite: `grep -rn "performSync" android/` só encontra `SyncLogic.kt` e `SyncForegroundService.kt`. Build passa.

Commit: `fix(android): single-owner sync — all announces/tsnet run in :sync process only`

**[DEVICE]** Smoke test: parear, "Sincronizar agora", ver status atualizar via broadcast; logout desconecta da tailnet.

### T1.2 — Logout precisa limpar identidade tsnet (coberto por T1.1, conferir)

Confirme que o handler `ACTION_LOGOUT` apaga `filesDir/tsgo-state` **no processo `:sync`** (é o dono dos arquivos abertos). Sem isso, o próximo usuário logado neste aparelho reutiliza a identidade de nó do usuário anterior.

Além disso: remova a propriedade `tailnetJoined` de `PairingStore` e o write dela em `SyncLogic.ensureTailnetConnected` — ela nunca é lida (código morto que sugere um mecanismo que não existe). Ajuste o kdoc de `ensureTailnetConnected` se mencionar a flag.

Commit: `chore(android): drop dead tailnetJoined flag`

### T1.3 — `LlamaServerManager.stop()/switchTo()` não espera o processo morrer

**Problema:** `stop()` chama `process?.destroy()` e retorna. `switchTo()` chama `stop()` e `start()` em seguida — o llama-server antigo ainda pode segurar a porta 8090 → o novo processo nasce, falha o bind, morre, e `start()` já retornou `true`. O loop de 30s mascara, mas o "Test" da ModelsScreen falha esporadicamente.

Correção em `LlamaServerManager`:
```kotlin
fun stop() {
    val p = process ?: return
    process = null
    p.destroy()
    if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
        p.destroyForcibly()
        p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
    }
}
```
`stop()` agora bloqueia até 7s — **nunca chame da main thread**. Em `ModelRuntimeService.onStartCommand`, o handler de `ACTION_STOP` roda na main thread: troque para `scope.launch { LlamaServerManager.stop(); broadcastStatus() }`. `onDestroy` também chama `stop()` na main thread do processo `:modelruntime`; aceitável em destroy, mas prefira `runBlocking`-free: mantenha como está e adicione comentário, ou mova para `Thread { ... }.start()`. Escolha simples: deixe o `onDestroy` como está (ANR em destroy de processo dedicado não derruba a UI).

Critério de aceite: build passa; nenhuma chamada de `stop()`/`switchTo()` em caminho de main thread fora de `onDestroy`.

Commit: `fix(android): wait for llama-server exit before restart (port 8090 race)`

### T1.4 — Estado do modelo entre processos: comando de consulta explícito

**Problema:** `ModelRegistry` usa `SharedPreferences` comum, escrito pelo processo `:modelruntime` e lido pelo processo UI. `SharedPreferences` não é multi-process: a UI pode ler valor velho do cache do próprio processo. Hoje a UI só se atualiza quando um broadcast chega — se nenhum evento acontece depois que a tela abre, ela mostra estado stale.

Correção (padrão request/response por broadcast):
1. `ModelRuntimeService`: nova action `ACTION_QUERY_STATUS = "com.sufficit.ai.mobiledevice.action.QUERY_STATUS"`, handler: `broadcastStatus()`. Helper no companion:
   ```kotlin
   fun queryStatus(context: Context) {
       val intent = Intent(context, ModelRuntimeService::class.java).setAction(ACTION_QUERY_STATUS)
       ContextCompat.startForegroundService(context, intent)
   }
   ```
2. `MainActivity`: dentro do `DisposableEffect` que registra o receiver de `ACTION_STATUS_CHANGED`, após registrar, chame `ModelRuntimeService.queryStatus(context)`.
3. `ModelsScreen`: idem — após registrar o receiver, `ModelRuntimeService.queryStatus(context)`.
4. Regra de ouro (adicione ao kdoc de `ModelRegistry`): *o processo `:modelruntime` é o único que ESCREVE `activeModelFileName`; processos de UI só leem como chute inicial e confiam nos broadcasts.* (Hoje já é assim — `handleSwitchTo`/`handleDelete`/`ensureModelProvisioned` escrevem no serviço; apenas documente.)

Commit: `fix(android): explicit status query for cross-process model state`

### T1.5 — Checagem de espaço em disco + validação GGUF no download

**Problema:** `ModelDownloader` baixa 1.1GB+ sem checar espaço livre; num aparelho de entrada isso enche o storage (o Android mata o app e degrada o sistema). E um arquivo truncado/corrompido com resume de servidor que mudou o conteúdo passaria batido.

Correção em `ModelDownloader.download` (mantendo assinatura):
1. Antes de abrir a conexão de download, descubra o tamanho esperado: se a resposta vier `200`, `body.contentLength()`; se `206`, o total do `Content-Range` (função `totalSize` já existe — reuse). A checagem de espaço deve ocorrer **depois** de obter a resposta (aí o tamanho é conhecido) e antes de começar a escrever:
   ```kotlin
   val required = total - alreadyExisting + SAFETY_MARGIN_BYTES   // SAFETY_MARGIN_BYTES = 250L * 1024 * 1024
   val stat = android.os.StatFs(destination.parentFile!!.absolutePath)
   if (total > 0 && stat.availableBytes < required) {
       return ModelDownloadResult.Failure(
           "espaço insuficiente: precisa de ~${required / (1024 * 1024)}MB livres"
       )
   }
   ```
   (onde `alreadyExisting` = bytes já no `.part` quando `resuming`, senão 0)
2. Depois do loop de escrita e ANTES do `renameTo`, valide o arquivo:
   ```kotlin
   // GGUF magic: primeiros 4 bytes são 'G','G','U','F'. Um .part truncado por
   // resume contra um arquivo remoto que mudou não pode virar modelo "instalado".
   val magic = ByteArray(4)
   java.io.FileInputStream(tmp).use { it.read(magic) }
   if (!magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))) {
       tmp.delete()
       return ModelDownloadResult.Failure("arquivo baixado não é GGUF válido — baixe novamente")
   }
   if (total > 0 && tmp.length() != total) {
       return ModelDownloadResult.Failure("download incompleto (${tmp.length()}/$total bytes) — tente de novo para retomar")
   }
   ```
   Atenção: no caso "incompleto", **não delete** o `.part` (resume). No caso "não é GGUF", delete (lixo irrecuperável).

Commit: `fix(android): free-space check + GGUF magic/size validation on download`

### T1.6 — Serializar downloads no `ModelRuntimeService`

**Problema:** cada `ACTION_DOWNLOAD` lança uma coroutine; a UI até bloqueia botões, mas o auto-provisionamento (`ensureModelProvisioned`) pode colidir com um download manual — dois downloads de ~1GB simultâneos num aparelho de 4GB.

Correção: um `kotlinx.coroutines.sync.Mutex` no serviço:
```kotlin
private val downloadMutex = kotlinx.coroutines.sync.Mutex()
```
- `handleDownload`: envolva o corpo em `downloadMutex.withLock { ... }`.
- `ensureModelProvisioned`: envolva a parte do download em `if (downloadMutex.tryLock()) { try { ... } finally { downloadMutex.unlock() } } else return` (se já tem download em andamento, o loop tenta de novo em 30s).

Commit: `fix(android): serialize model downloads`

### T1.7 — Foreground service: `dataSync` não sobrevive ao Android 15

**Problema:** ambos os serviços usam `foregroundServiceType="dataSync"`. Com `targetSdk 35`, no Android 15+:
- FGS `dataSync` tem **teto de 6h por 24h** — depois disso o sistema derruba o serviço. Incompatível com "fica rodando sempre".
- FGS `dataSync` **não pode ser iniciado a partir de `BOOT_COMPLETED`** — o `BootCompletedReceiver` falharia.

O Galaxy A51 (Android 11) não sofre disso, mas qualquer aparelho novo sim. O tipo correto para "provedor de inferência sempre-ativo" é `specialUse`.

Passos:
1. `AndroidManifest.xml`:
   - Troque a permission `FOREGROUND_SERVICE_DATA_SYNC` por:
     ```xml
     <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
     ```
   - Nos DOIS `<service>`, troque `android:foregroundServiceType="dataSync"` por `android:foregroundServiceType="specialUse"` e adicione DENTRO de cada `<service>`:
     ```xml
     <property
         android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
         android:value="Always-on on-device AI inference provider reachable over a private tailnet; must outlive 6h dataSync limits to keep serving embeddings." />
     ```
2. Nenhuma mudança de código: `startForeground(id, notification)` herda o tipo declarado no manifest.

Critério de aceite: `assembleDebug` passa; `grep -n dataSync android/app/src/main/AndroidManifest.xml` vazio.

Commit: `fix(android): specialUse FGS type — survive Android 15 dataSync 6h cap and boot restrictions`

---

## Fase 2 — Segurança

### T2.1 — Proxy POC não pode escutar em `0.0.0.0` por padrão

**Problema:** `src/config.js` usa `PROXY_HOST ?? "0.0.0.0"` — o README promete "nothing is exposed on the LAN", mas o próprio proxy expõe o embeddings do telefone para a LAN inteira, sem auth.

Passos:
1. `src/config.js`: default vira `"127.0.0.1"`.
2. `.env.example`: `PROXY_HOST=127.0.0.1` com comentário `# 0.0.0.0 exposes the proxy to the whole LAN — only do that behind a firewall you trust`.
3. `README.md`: ajuste a seção API mencionando que o bind é local por padrão.
4. Suporte opcional a API key em `src/server.js`: se `process.env.PROXY_API_KEY` estiver definida (adicione `proxyApiKey: process.env.PROXY_API_KEY ?? ""` no config), toda rota `/v1/*` exige header `Authorization: Bearer <key>`; senão 401 `{"error":"unauthorized"}`. `/health` fica sempre aberto.

Commit: `fix(proxy): bind localhost by default, optional bearer auth`

### T2.2 — Proxy: limitar corpo e rejeitar `encoding_format` não suportado

1. Em `readBody`, acumule com limite (10MB); passou disso, responda `413 {"error":"payload_too_large"}` e destrua a request.
2. Em `handleEmbeddings`, antes de encaminhar: se `payload.encoding_format` existir e for diferente de `"float"`, responda `400 {"error":"unsupported_encoding_format"}` — o pós-processamento de `dimensions` faz `slice` no array e produziria lixo silencioso num embedding base64.

Commit: `fix(proxy): body size cap + reject non-float encoding_format`

### T2.3 — Nota de segurança local do llama-server (documentação apenas)

Adicione ao `android/README.md`, seção nova "Superfície local": o llama-server escuta em `127.0.0.1:8090` sem auth — qualquer app no aparelho pode gerar embeddings. Aceito por ora (dado não sensível, só gasta CPU); se um dia servir modelos de geração, ligar `--api-key` com chave por device vinda do announce. **Não implemente a api-key agora** (exige mudança coordenada no backend).

Commit: `docs(android): document local llama-server exposure`

---

## Fase 3 — Robustez

### T3.1 — Keep-alive por saúde real, não só processo vivo

**Problema:** o loop do `ModelRuntimeService` só checa `process.isAlive`. llama-server pode estar vivo e travado (OOM em curso, deadlock) — o aparelho fica "verde" servindo nada.

Correção em `ModelRuntimeService.ensureLoopRunning()`: quando `LlamaServerManager.isRunning()` for true, faça um GET `http://127.0.0.1:${LlamaServerManager.port}/health` com timeout de 3s (pode instanciar um OkHttpClient de campo, ou reusar `LocalEmbeddingTester` extraindo um método `isHealthy(port): Boolean`). Se falhar **3 iterações seguidas** (contador no serviço), `LlamaServerManager.switchTo(applicationContext, activeFile)` para reciclar e zere o contador. Não recicle na primeira falha — carga de modelo demora e o loop roda a cada 30s.

Commit: `fix(android): health-based keep-alive with 3-strike restart`

### T3.2 — Proteção térmica

Telefone servindo inferência plugado no carregador esquenta. Sem proteção, o SoC throttla até o sistema matar o processo.

Em `ModelRuntimeService` (dentro do loop de 30s, antes das outras checagens):
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    val pm = getSystemService(android.os.PowerManager::class.java)
    val status = pm?.currentThermalStatus ?: android.os.PowerManager.THERMAL_STATUS_NONE
    if (status >= android.os.PowerManager.THERMAL_STATUS_SEVERE) {
        if (LlamaServerManager.isRunning()) {
            LlamaServerManager.stop()
            updateNotification("Pausado: aparelho quente demais (proteção térmica)")
            broadcastStatus()
        }
        delay(LOOP_INTERVAL_MS)
        continue
    }
}
```
O religamento é automático: quando a temperatura baixar de SEVERE, o fluxo normal do loop (`!isRunning -> start`) religa o servidor. Cuidado: o `broadcastStatus()` atual sobrescreve a notificação com "Nenhum modelo ativo" — aceite isso (simplicidade) ou pule o `updateNotification` dentro de `broadcastStatus` quando pausado por térmica; escolha a opção simples (aceitar).

Commit: `feat(android): thermal pause for llama-server at SEVERE status`

### T3.3 — Reconectar tsnet quando a rede muda

**Problema:** as interfaces de rede são fotografadas UMA vez (`SetInterfacesJSON` antes do `Start`). WiFi→4G ou troca de rede deixa o tsnet com lista de interfaces velha até o processo morrer.

Em `SyncForegroundService.onCreate`, registre um callback de rede:
```kotlin
private var networkCallback: ConnectivityManager.NetworkCallback? = null

// em onCreate, após criar os campos:
val cm = getSystemService(ConnectivityManager::class.java)
networkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: android.net.Network) {
        // Rede nova: derruba o tsnet para que o próximo syncOnce() reconecte
        // com interfaces frescas (start() re-coleta via SetInterfacesJSON).
        scope.launch {
            if (tailscale.isRunning()) tailscale.stop()
            syncOnce()
        }
    }
}
cm?.registerDefaultNetworkCallback(networkCallback!!)
// em onDestroy: networkCallback?.let { cm.unregisterNetworkCallback(it) } — guarde o cm num campo
```
Atenção: `onAvailable` também dispara na subida inicial do serviço; `syncOnce()` é idempotente, sem problema.

Commit: `fix(android): rejoin tailnet with fresh interfaces on network change`

### T3.4 — Migrar o modelo legado `llama/model.gguf`

`Config.kt` mantém `ModelFile()` "para não perder" o arquivo antigo, mas nada nunca migra nem serve esse arquivo — hoje é peso morto que ocupa até 2.3GB no aparelho de quem instalou versão velha.

Em `ModelRuntimeService.onCreate`:
```kotlin
val legacy = ModelFile(applicationContext)
if (legacy.exists()) {
    val target = File(ModelsDir(applicationContext), "legacy-model.gguf")
    ModelsDir(applicationContext).mkdirs()
    if (legacy.renameTo(target)) {
        if (registry.activeModelFileName == null) registry.activeModelFileName = target.name
        legacy.parentFile?.delete()
    }
}
```
E remova o comentário/kdoc de `ModelFile` que prometia a preservação passiva; a função passa a existir só para a migração (documente isso nela).

Commit: `fix(android): migrate legacy single-model file into models dir`

### T3.5 — Validar pairing token antes de entrar na Home (Modo A)

**Problema:** `TokenPairingScreen.onPair` grava o token e navega — token errado/expirado só explode depois, num "Falha na sincronização" genérico.

Correção (usa o mecanismo do T1.1):
1. `MainActivity`, no `onPair`: mantenha `store.pairingToken = token`, inicie o serviço, chame `SyncForegroundService.syncNow(context)` e **não navegue ainda** — mostre loading na própria tela (novo par de estados `pairingLoading/pairingStatus` passados ao `TokenPairingScreen`, análogos aos de login).
2. No receiver de `ACTION_SYNC_STATE` (já criado em T1.1): se a tela atual é `TOKEN_PAIRING` (guarde um flag `awaitingPairingValidation`), então:
   - sucesso → `refreshHomeUser()`, navega HOME (popUpTo LOGIN inclusive), pede permissão de notificação.
   - falha → `store.pairingToken = null`, mostra o erro em `pairingStatus`, fica na tela.
3. `TokenPairingScreen`: adicione parâmetros `loading: Boolean, status: String?` — desabilite botão/mostre spinner e status (copie o padrão visual do `LoginScreen`).

Commit: `feat(android): validate pairing token via announce before entering Home`

### T3.6 — Auto-provisionamento ciente de RAM

**Problema:** `ensureModelProvisioned` pega `recommended.first()` (1.12GB) sem olhar o aparelho. Num device com menos RAM que o A51, OOM garantido.

Em `ModelRuntimeService.ensureModelProvisioned`, troque a escolha:
```kotlin
val am = getSystemService(android.app.ActivityManager::class.java)
val memInfo = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
val totalGB = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
// Modelo precisa caber na RAM com folga: peso do arquivo * ~1.5 (KV cache, buffers) + SO.
val pick = DeviceModelCatalog.recommended.firstOrNull { it.sizeGB * 1.5 < totalGB - 1.5 }
    ?: DeviceModelCatalog.recommended.minByOrNull { it.sizeGB }
    ?: return
```

Commit: `fix(android): pick auto-provisioned model by available RAM`

---

## Fase 4 — UI/UX e visual

### T4.1 — ModelsScreen: corrigir estrutura de scroll (bug visível)

**Problema:** a tela é uma `Column` fixa (recomendados + instalados + busca) com uma `LazyColumn(weight(1f))` só para resultados. Com 2+ recomendados e alguns instalados, o conteúdo fixo estoura a tela em aparelhos pequenos e NADA rola — a busca fica inacessível.

Correção: a tela inteira vira UMA `LazyColumn`. Estrutura alvo (pseudocódigo dos `item {}`):
```kotlin
LazyColumn(
    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    contentPadding = PaddingValues(vertical = 16.dp)
) {
    item { /* Row: back + título "Modelos de IA" */ }
    item { actionError?.let { ... } }
    item { /* título + subtítulo "Recomendado para este aparelho" */ }
    items(DeviceModelCatalog.recommended) { known -> /* card recomendado (código atual) */ }
    item { HorizontalDivider() }
    item { Text("Instalados"...) }
    if (installedModels.isEmpty()) item { Text("Nenhum modelo baixado ainda."...) }
    items(installedModels, key = { it.name }) { file -> InstalledModelRow(...) }
    item { HorizontalDivider() }
    item { /* título "Buscar no Hugging Face" + Row(TextField + botão) */ }
    searchError?.let { item { Text(...) } }
    items(searchResults, key = { it.repoId }) { summary -> SearchResultCard(...) }
}
```
Sem lógica nova — só re-layout. Mantenha todos os handlers e estados.

Commit: `fix(android): ModelsScreen scrolls as a single lazy list`

### T4.2 — Confirmação antes de deletar modelo

Delete hoje é instantâneo e destrutivo (re-baixar custa 1GB). Em `ModelsScreen`:
```kotlin
var pendingDelete by remember { mutableStateOf<File?>(null) }
// no InstalledModelRow: onDelete = { pendingDelete = file }
pendingDelete?.let { file ->
    AlertDialog(
        onDismissRequest = { pendingDelete = null },
        title = { Text("Remover modelo?") },
        text = { Text(if (file.name == activeModelName)
            "\"${file.name}\" é o modelo ATIVO — removê-lo para o servidor de inferência. Baixar de novo custa ~1GB."
            else "Remover \"${file.name}\"? Baixar de novo custa ~1GB.") },
        confirmButton = { TextButton(onClick = { deleteModel(file); pendingDelete = null }) { Text("Remover") } },
        dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") } }
    )
}
```

Commit: `feat(android): confirm dialog before model delete`

### T4.3 — Home vira dashboard de status real

Hoje: `networkAddress` sempre null, nenhum sinal de servidor rodando/última sincronização. Os dados JÁ chegam via broadcasts (T1.1 e T1.4).

1. `MainActivity`: novos estados `networkAddress` (T1.1), `lastSyncAtMs: Long?`, `lastSyncOk: Boolean?` (do broadcast `ACTION_SYNC_STATE`), `modelRunning: Boolean` (do `EXTRA_RUNNING` de `ACTION_STATUS_CHANGED` — hoje ignorado). Passe todos ao `HomeScreen`.
2. `HomeScreen`, card "Dispositivo": acima do endereço, uma linha de status:
   ```kotlin
   Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
       Box(Modifier.size(10.dp).clip(CircleShape).background(
           when {
               modelRunning && lastSyncOk == true -> Color(0xFF2E7D32)   // verde: servindo + sincronizado
               modelRunning || lastSyncOk == true -> Color(0xFFF9A825)   // âmbar: metade funcionando
               else -> Color(0xFFC62828)                                  // vermelho: nada
           }
       ))
       Text(
           when {
               modelRunning && lastSyncOk == true -> "Operacional"
               modelRunning -> "Modelo ativo, aguardando sincronização"
               lastSyncOk == true -> "Sincronizado, modelo parado"
               else -> "Aguardando"
           },
           style = MaterialTheme.typography.bodyMedium
       )
   }
   ```
3. "Endereço de rede": mostre `networkAddress` real quando existir (tailnet IP); fallback no texto atual.
4. Abaixo do botão sincronizar: `lastSyncAtMs?.let { Text("Última sincronização: ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}", style = bodySmall, color = onSurfaceVariant) }`.
5. Card "Modelo de IA": junto do nome, um badge textual `if (modelRunning) "· rodando" else "· parado"` em `bodySmall`.

Commit: `feat(android): home shows live status — server state, tailnet ip, last sync`

### T4.4 — Estado de UI sobrevive à rotação

Troque `remember { mutableStateOf(...) }` por `rememberSaveable { mutableStateOf(...) }` para estados **primitivos** de UI (String?/Boolean/Float):
- `MainActivity`: `loginLoading`, `loginStatus`, `syncLoading`, `syncStatus`, e os novos de T4.3 (`networkAddress`, `lastSyncAtMs`... — todos primitivos ou String).
- `ModelsScreen`: `query`, `searchError`, `actionError`, `expandedRepo`, `downloadingFile`, `downloadProgress`, `activeModelName`, `testingFile`.
- `TokenPairingScreen`: `token`.
NÃO tente salvar `searchResults`, `repoFiles`, `installedModels`, `testResults`, `homeUser` (tipos não-saveable) — deixe como `remember`.

Commit: `fix(android): survive rotation with rememberSaveable for primitive ui state`

### T4.5 — Strings hardcoded → `strings.xml`

Todas as strings de UI estão em Kotlin. Mova para `android/app/src/main/res/values/strings.xml` e use `stringResource(R.string.xxx)` (import `androidx.compose.ui.res.stringResource`). Cubra: LoginScreen, TokenPairingScreen, HomeScreen, SettingsScreen, ModelsScreen, e os textos de notificação dos dois serviços (`getString(R.string.xxx)` no service). Padrão de nome: `login_title`, `login_subtitle`, `home_sync_now`, `models_delete_confirm_title`, etc.

Correções de texto no caminho:
- `"Test"` / `"Test (reinicia o servidor)"` → `"Testar"` / `"Testar (reinicia o servidor)"` (mistura EN/PT).
- Mensagens dinâmicas (`"Falha no login: ${ex.message}"`) usam placeholders: `<string name="login_failed">Falha no login: %1$s</string>` + `stringResource(R.string.login_failed, msg)`; em services, `getString(...)`.
- Strings construídas fora de composable/context (ex.: `SyncLogic`, `ModelDownloader` retornam mensagens) **ficam como estão** — não force context onde não existe.

Tarefa mecânica e longa; faça arquivo por arquivo, compilando a cada arquivo.

Commit: `refactor(android): move ui strings to resources`

### T4.6 — Edge-to-edge + tema escuro no nível da janela

1. `MainActivity.onCreate`, primeira linha após `installSplashScreen()`: `androidx.activity.enableEdgeToEdge()` (import `androidx.activity.enableEdgeToEdge`).
2. Envolva o conteúdo raiz em insets: em `SufficitApp`, o `NavHost` já está dentro de `Surface` — adicione `Modifier.safeDrawingPadding()` no `Surface` (import `androidx.compose.foundation.layout.safeDrawingPadding`) para nada colar em statusbar/navbar.
3. Tema escuro da janela: crie `android/app/src/main/res/values-night/themes.xml`:
   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <resources>
       <style name="Theme.SufficitMobileAI" parent="android:Theme.Material.NoActionBar">
           <item name="android:windowBackground">@color/sufficit_canvas_dark</item>
       </style>
       <style name="Theme.SufficitMobileAI.Splash" parent="Theme.SplashScreen">
           <item name="windowSplashScreenBackground">@color/sufficit_surface_dark</item>
           <item name="windowSplashScreenAnimatedIcon">@drawable/ic_launcher_foreground</item>
           <item name="postSplashScreenTheme">@style/Theme.SufficitMobileAI</item>
       </style>
   </resources>
   ```
   E em `values/colors.xml` adicione `sufficit_canvas_dark = #15171A` e `sufficit_surface_dark = #1F2226` (mesmos hex do `SufficitTheme.kt`). Sem isso, abrir o app no escuro pisca branco antes do Compose pintar.

Commit: `feat(android): edge-to-edge + dark window theme (no white flash)`

### T4.7 — Progresso de download com porcentagem

`LinearProgressIndicator` de 60dp sem número é ilegível para um download de 15 minutos. Nos dois lugares de `ModelsScreen` que mostram progresso (card recomendado e arquivo de busca), envolva numa `Column` e acrescente `Text("${(downloadProgress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)`. Aumente a largura da barra para 80.dp.

Commit: `feat(android): show download percentage`

### T4.8 — Previews Compose

Adicione `@Preview` composables no fim de `LoginScreen.kt`, `HomeScreen.kt`, `SettingsScreen.kt`, `TokenPairingScreen.kt` (os stateless). Padrão:
```kotlin
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun LoginScreenPreview() {
    SufficitTheme { LoginScreen(loading = false, status = null, onLoginClick = {}, onUseTokenClick = {}) }
}
```
`ModelsScreen` depende de context/serviço — pule.

Commit: `chore(android): compose previews for stateless screens`

---

## Fase 5 — Build, release e testes

### T5.1 — Testes unitários (primeiros do repo)

Dependências em `android/app/build.gradle.kts`:
```kotlin
testImplementation("junit:junit:4.13.2")
testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
```
Criar `android/app/src/test/java/com/sufficit/ai/mobiledevice/`:

1. `ModelDownloaderTest.kt` — usa MockWebServer + pasta temporária (`@get:Rule TemporaryFolder` ou `createTempDirectory`):
   - download completo 200 com corpo iniciando em "GGUF" → Success, arquivo final existe, `.part` não existe;
   - corpo que NÃO inicia com GGUF → Failure, `.part` deletado;
   - resume: crie `dest.part` com N bytes, server responde 206 com `Content-Range` → Success e conteúdo = concatenação;
   - server responde 200 ignorando o Range → arquivo reiniciado do zero (conteúdo = só a resposta);
   - erro no meio (MockWebServer `SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY`) → Failure e `.part` PRESERVADO.
   - Nota: o StatFs de T1.5 não roda em JVM pura — envolva a checagem de espaço em try/catch retornando "sem checagem" quando `StatFs` lançar (Robolectric não está no projeto; documente no código: `// StatFs unavailable in JVM unit tests — skip check`). Alternativa aceitável: extrair a checagem para uma lambda `availableBytes: (File) -> Long?` injetável com default usando StatFs, e nos testes injetar `{ null }`. Prefira a lambda injetável.
2. `PairingApiTest.kt` — MockWebServer:
   - 200 com providerId + campos tailnet → Success com todos os campos;
   - 200 com `"tailnetJoinKey": null` → Success com joinKey null;
   - 401 com `{"error":"invalid token"}` → Failure("invalid token");
   - 500 sem corpo JSON → Failure("HTTP 500");
   - conexão recusada → Failure (mensagem não vazia).
   - Para apontar o `PairingApi` ao MockWebServer, passe `gatewayBaseUrl = server.url("/").toString()`. O `Ipv4OnlyDns` resolve localhost normal.

Proxy Node — criar `test/embeddings.test.js` com `node:test`: extraia `l2Normalize` e a validação de `dimensions` de `src/server.js` para `src/embeddings.js` (export puro), importe em `server.js`, e teste: norma 1 após normalize; vetor zero permanece zero; dimensions inválidas (0, -1, 1.5, 99999) rejeitadas; truncamento preserva prefixo. Adicione script `"test": "node --test"` no `package.json`.

Rodar: `./gradlew :app:testDebugUnitTest` e `npm test`.

Commit: `test: unit tests for downloader, pairing api, proxy embedding math`

### T5.2 — R8/minify no release + regras

Em `android/app/build.gradle.kts`, bloco `release`:
```kotlin
isMinifyEnabled = true
isShrinkResources = true
```
Em `proguard-rules.pro` acrescente:
```
# gomobile bindings — accessed via generated JNI glue, must survive
-keep class tsgo.** { *; }
-keep class go.** { *; }
# AppAuth uses reflection over its own model classes in places
-keep class net.openid.appauth.** { *; }
```
Validação: `./gradlew :app:assembleRelease` deve passar (o APK sai unsigned — ok).

**[DEVICE]** smoke test do APK release (assinar com debug key: `apksigner`/instalar via Android Studio) antes de qualquer distribuição.

Commit: `build(android): enable r8 minify+shrink with keep rules`

### T5.3 — abiFilters + limpeza de dependências

1. `defaultConfig`: adicione
   ```kotlin
   ndk { abiFilters += "arm64-v8a" }
   ```
2. Remova `androidx.work:work-runtime-ktx` (nunca usado — `grep -rn "androidx.work" android/app/src/main/java/` para confirmar antes).
3. Mantenha `lifecycle-viewmodel-compose` (uso futuro planejado).

Commit: `build(android): arm64-only abi, drop unused workmanager dep`

### T5.4 — Bump de versão

Regra do projeto: **sempre** subir versão antes de empacotar/instalar. `versionCode = 2`, `versionName = "0.2.0"`. Daqui em diante, todo lote de mudanças que gera APK instala versão nova (+1 no code).

Commit: `chore(android): bump to 0.2.0 (2)`

### T5.5 — CI (GitHub Actions)

Criar `.github/workflows/ci.yml`:
```yaml
name: ci
on:
  push:
    branches: [main]
  pull_request:
jobs:
  android:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          lfs: true
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - uses: android-actions/setup-android@v3
      - run: cd android && ./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest
  proxy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: "20"
      - run: npm test
```
Se T0.2 caiu no fallback sem LFS, remova o `lfs: true`. Se o remote não for GitHub (checar `git remote -v`; projetos Sufficit às vezes são Radicle), **pule esta tarefa** e registre.

Commit: `ci: build + unit tests for android and proxy`

### T5.6 — Atualizar documentação de status

`android/README.md`: a seção "Status" está congelada em 2026-07-09 e diz que Tailscale embarcado / llama-server embarcado / heartbeat "não estão implementados" — **estão** (tsgo, LlamaServerManager, SyncForegroundService). Reescreva a seção Status refletindo o real:
- implementados: tsnet embarcado (tsgo/gomobile), llama-server embarcado (subprocess de jniLibs), model manager (busca HF/download/switch/test), heartbeat FGS com política de bateria, boot receiver, proteção térmica (T3.2), keep-alive por saúde (T3.1);
- pendentes: login OAuth completo verificado com conta real; catálogo de modelos vindo do backend (hoje hardcoded p/ A51); assinatura/distribuição de release.
Acrescente seção curta "Arquitetura de processos" com o diagrama textual: processo UI (MainActivity) ↔ broadcasts ↔ `:sync` (announce + tsnet, dono único — T1.1) e `:modelruntime` (llama-server + downloads, dono único do registry — T1.4).

Commit: `docs(android): update status and process-architecture sections`

---

## Fase 6 — Backlog / itens que exigem coordenação (NÃO implementar agora)

Registrar apenas; qualquer um deles exige decisão humana ou mudança em outro repo:

1. **Backend: token na URL** — `POST /mobile/{token}/announce` põe segredo em path (vaza em log de proxy/haproxy). Preferência da casa é querystring/header. Mudar no sufficit-ai e aqui em `PairingApi` em conjunto, com período de compatibilidade.
2. **Catálogo de modelos por device vindo do backend** — substituir `DeviceModelCatalog` hardcoded por fetch keyed por `Build.MODEL`/`Build.HARDWARE` (a metade "networked" já descrita no kdoc do catálogo).
3. **api-key no llama-server via announce** — backend gera chave por device, announce devolve, `LlamaServerManager` passa `--api-key`, backend usa a chave no dispatch.
4. **Ícone/branding** — ícone atual usa o espiral vermelho antigo; accent do app é laranja. Regerar adaptive icon com a marca atual (precisa de asset de design).
5. **Vulkan/GPU por allowlist** — retomar a Fase 5 antiga (Mali-G72 SIGSEGV) com allowlist por GPU consultada no backend.
6. **Múltiplos telefones no proxy POC** — fora de escopo v1, segue fora.
7. **ViewModel completo por tela** — T4.4 (rememberSaveable) resolve rotação; migração para ViewModel só se a complexidade de estado crescer.
8. **Dependências major** — AGP 8.8/Kotlin 2.1.10/BOM 2025.02 estão funcionais; bump em lote separado com teste em aparelho.

---

## Relatório final exigido

Ao terminar, produza um resumo com:
1. Tabela tarefa → commit hash → status (feita / pulada+motivo).
2. Lista dos **[DEVICE]** pendentes de verificação física.
3. Saída de `./gradlew :app:assembleDebug :app:testDebugUnitTest` e `npm test` (últimas linhas).
4. Tamanho final do APK debug (`ls -la android/app/build/outputs/apk/debug/`).
