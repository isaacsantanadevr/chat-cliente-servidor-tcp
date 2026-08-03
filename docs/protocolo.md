# Protocolo de aplicação

## Transporte e framing

Todas as mensagens usam TCP, JSON e UTF-8. Cada objeto JSON termina em `\n`:

```text
{"version":1,"type":"LIST_USERS"}\n
```

O receptor usa `BufferedReader.readLine()`. Uma mensagem pode chegar fragmentada em vários pacotes TCP ou várias mensagens podem chegar no mesmo pacote; o delimitador, e não o pacote, define a fronteira.

Campos comuns:

| Campo | Tipo | Regra |
|---|---|---|
| `version` | inteiro | atualmente `1` |
| `type` | string | um dos tipos documentados |
| `timestamp` | string | UTC no formato ISO-8601 quando enviado pelo servidor |

Campos ausentes no JSON são omitidos. Tipos inesperados no estado atual recebem `ERROR`.

## Tipos

| Tipo | Direção | Campos principais | Função |
|---|---|---|---|
| `LOGIN` | C→S | `username` | solicita autenticação do socket |
| `LOGIN_OK` | S→C | `username` | confirma o nome |
| `BROADCAST` | C→S | `content` | mensagem geral |
| `PRIVATE_MESSAGE` | C→S | `to`, `content` | mensagem privada |
| `CHAT_MESSAGE` | S→C | `scope`, `from`, `to?`, `content`, `timestamp` | entrega de chat |
| `LIST_USERS` | C→S | - | solicita lista |
| `USER_LIST` | S→C | `users` | lista atualizada |
| `USER_JOINED` | S→C | `username`, `timestamp` | entrada |
| `USER_LEFT` | S→C | `username`, `timestamp` | saída |
| `FILE_START` | ambos | metadados da transferência | inicia arquivo privado |
| `FILE_CHUNK` | ambos | `transferId`, `chunkIndex`, `data` | bloco Base64 |
| `FILE_END` | ambos | `transferId`, `sha256` | encerra e valida |
| `FILE_RECEIVED` | ambos | `transferId`, `status` | confirma recepção |
| `QUIT` | C→S | - | saída controlada |
| `BYE` | S→C | - | confirma encerramento |
| `ERROR` | S→C | `code`, `error` | falha recuperável |

`FILE_PROGRESS` é um evento local Java→React e nunca é necessário no fio TCP.

## Login

```json
{"version":1,"type":"LOGIN","username":"isaac"}
```

O nome aceita 3 a 20 letras Unicode, números ou `_`. A comparação ignora maiúsculas/minúsculas. O campo `from` recebido de clientes não é usado.

## Mensagens

Broadcast enviado pelo cliente:

```json
{"version":1,"type":"BROADCAST","content":"Olá, pessoal"}
```

Entrega criada pelo servidor:

```json
{"version":1,"type":"CHAT_MESSAGE","scope":"BROADCAST","from":"isaac","content":"Olá, pessoal","timestamp":"2026-08-03T18:30:00Z"}
```

Mensagem privada:

```json
{"version":1,"type":"PRIVATE_MESSAGE","to":"maria","content":"Olá"}
```

Somente remetente e destinatário recebem a entrega `scope: PRIVATE`. Destino ausente produz `USER_NOT_FOUND`.

## Arquivos

Início:

```json
{"version":1,"type":"FILE_START","transferId":"1cbd47b3-7091-4c70-82ae-5fa981ff67ce","to":"maria","fileName":"relatorio.pdf","fileSize":500000,"totalChunks":21}
```

Bloco:

```json
{"version":1,"type":"FILE_CHUNK","transferId":"1cbd47b3-7091-4c70-82ae-5fa981ff67ce","chunkIndex":0,"data":"AAECAwQ="}
```

Fim:

```json
{"version":1,"type":"FILE_END","transferId":"1cbd47b3-7091-4c70-82ae-5fa981ff67ce","sha256":"hash-hexadecimal"}
```

Limites e validações:

- arquivo máximo padrão: 10 MiB;
- bloco bruto máximo: 24 KiB;
- `transferId`: UUID;
- índices começam em zero e são estritamente sequenciais;
- `totalChunks` deve corresponder ao tamanho declarado;
- nomes não podem conter diretórios ou `..`;
- Base64 nunca é enviado ao histórico da UI ou a logs;
- servidor e receptor validam tamanho e SHA-256;
- o receptor grava `.part` e só renomeia após validação.

## Erros

Os códigos principais são `INVALID_MESSAGE`, `LOGIN_REQUIRED`, `INVALID_USERNAME`, `USERNAME_IN_USE`, `USER_NOT_FOUND`, `UNEXPECTED_TYPE`, `FILE_TRANSFER_FAILED` e `PROCESSING_ERROR`. Um erro de conteúdo ou arquivo é respondido no mesmo socket e não encerra o chat. Falha de transporte, por outro lado, dispara a limpeza da conexão.
