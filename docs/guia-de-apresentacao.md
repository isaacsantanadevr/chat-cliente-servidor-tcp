# Guia de apresentação

## Sequência sugerida da demonstração

1. Mostre `ChatServer.start`: bind em `0.0.0.0`, laço de `accept` e envio ao executor.
2. Inicie o servidor e três clientes em máquinas/processos diferentes.
3. Demonstre nome duplicado, broadcast, privado e lista atualizada.
4. Envie um arquivo privado e mostre progresso, `.part`, conclusão e botão Abrir.
5. Desconecte um cliente normalmente e finalize outro de modo abrupto.
6. Mostre que os demais continuam conversando.

## Como explicar o código

### Como o servidor aceita clientes e usa threads

`ServerSocket.accept()` bloqueia até chegar uma conexão. O socket retornado é entregue a `Executors.newCachedThreadPool()`. Cada tarefa fica em seu próprio `readLine`, então um cliente lento não impede os outros. O executor reutiliza threads ociosas e cria novas quando necessário.

### Como nomes duplicados são evitados

O servidor valida 3 a 20 letras, números ou underscore e converte somente a chave do mapa para minúsculas. `putIfAbsent` verifica e cadastra de forma atômica. Assim, dois logins simultâneos para `Isaac` e `isaac` não passam juntos, mas o nome original continua visível.

### Como funcionam broadcast e privado

No broadcast, o servidor cria uma nova `CHAT_MESSAGE`, preenche `from` usando a conexão e percorre uma cópia dos clientes, incluindo quem enviou. No privado, busca o destino normalizado e escreve apenas nos dois sockets. O cliente nunca controla o remetente.

### Como a desconexão é limpa

Saída por `QUIT`, fim do fluxo e exceção de socket convergem para o `finally`. `remove(chave, conexão)` garante que somente a conexão realmente registrada gere `USER_LEFT`. Depois a lista é publicada novamente.

### Como arquivos são divididos e validados

O cliente lê 24 KiB por vez, transforma apenas aquele bloco em Base64 e atualiza SHA-256. UUID diferencia transferências simultâneas. Servidor e receptor exigem índices 0, 1, 2... e contam bytes. O receptor escreve `.part`; tamanho e hash corretos permitem a renomeação. Caso contrário, exclui o temporário.

### Por que SHA-256

TCP detecta erros de transmissão e retransmite, mas SHA-256 também verifica a reconstrução completa e as regras do protocolo de arquivo. Qualquer diferença de conteúdo produz outro digest e impede que o `.part` seja tratado como concluído.

### Por que TCP

TCP entrega em ordem, retransmite perdas e possui controle de fluxo e congestionamento. Mensagens e arquivos precisam dessas propriedades. Com UDP, seria necessário implementar sequência, confirmação, timeout e retransmissão no projeto.

### Por que `0.0.0.0`

No servidor, `0.0.0.0` significa escutar em todas as interfaces IPv4, inclusive a placa da rede do laboratório. No cliente, deve ser usado o IPv4 real do servidor. `localhost` aponta para a própria máquina e não serve entre computadores.

### Como React conversa com Java

O WebView expõe `javaBridge` ao JavaScript. React chama métodos simples, como `sendBroadcast`. A ponte delega ao cliente TCP. No caminho inverso, `FrontendNotifier` serializa o evento e chama `window.receiveChatEvent` com `Platform.runLater`, pois o WebEngine deve ser atualizado na thread JavaFX.

## Perguntas que o professor pode fazer

**Uma chamada a `read()` é uma mensagem?** Não. TCP é um fluxo. O projeto usa `\n` como framing e `readLine()` acumula até o delimitador.

**O que impede mensagens JSON de se misturarem?** Cada conexão tem um lock de escrita que cobre `write`, nova linha e `flush`.

**Por que `ConcurrentHashMap` não basta para nome único?** Basta somente com uma operação atômica como `putIfAbsent`; fazer `containsKey` seguido de `put` teria condição de corrida.

**O servidor confia no `from`?** Não. Ele ignora esse campo de entrada e usa o usuário associado ao socket.

**O que acontece se o destino sair durante o arquivo?** O servidor remove a transferência, avisa o remetente quando possível e o receptor apaga o `.part`; o chat permanece conectado.

**Por que Base64?** Para manter um único protocolo JSON orientado a linhas. É simples e didático, com custo de aproximadamente 33% em tamanho.

**Como provar que o sistema não depende de localhost?** O host vem da tela/argumentos, o servidor faz bind configurável e o teste de laboratório usa o IPv4 obtido por `ipconfig` ou `ip addr`.

**Quais são as limitações?** Sem TLS, senha, persistência ou retomada de arquivo; são escolhas para manter foco em sockets e concorrência.

## Roteiro do vídeo obrigatório

Grave uma tela mostrando: comando do servidor, três clientes simultâneos, broadcast aparecendo nos três, privado restrito a dois, envio de arquivo, saída de um cliente e lista atualizada. Inclua por alguns segundos o IPv4/porta usados e narre por que a configuração funciona entre máquinas.
