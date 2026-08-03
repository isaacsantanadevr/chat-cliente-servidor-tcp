# Conecta - Chat cliente-servidor TCP

Aplicação de chat para múltiplos usuários em rede local. O servidor Java aceita conexões TCP, valida nomes únicos e encaminha mensagens gerais, privadas e arquivos. O cliente Java usa JavaFX/WebView para exibir uma interface React compilada como recurso estático, sem servidor HTTP.

## Tecnologias

- Java 17, Maven, `ServerSocket`, `Socket` e `ExecutorService`;
- Jackson para JSON UTF-8 delimitado por quebra de linha;
- JavaFX WebView e ponte JavaScript;
- React e Vite, com CSS próprio;
- JUnit 5 para testes unitários e de integração.

## Estrutura

```text
frontend/                    React, Vite e CSS
src/main/java/chat/
  protocol/                  mensagens e codec JSON
  server/                    servidor concorrente e roteamento
  client/                    cliente TCP
  transfer/                  regras, SHA-256 e arquivos .part
  ui/                        JavaFX, WebView e ponte JavaScript
src/main/resources/web/      frontend compilado
src/test/java/               testes automatizados
docs/                        arquitetura, protocolo, testes e relatório
scripts/                     build do frontend e execução
data/downloads/              arquivos recebidos
```

## Pré-requisitos

- JDK 17 (confirme com `java -version`);
- Maven 3.9 ou mais recente, ou apenas o Maven Wrapper incluído no projeto;
- Node.js 20 ou mais recente e npm (necessários somente para recompilar o frontend).

O servidor não usa Node.js. Como o frontend compilado está incluído em `src/main/resources/web`, uma máquina que apenas executa o servidor precisa somente de Java e Maven.

## Compilar

Recompile a interface sempre que alterar `frontend/src`:

```bash
cd frontend
npm install
npm run build
cd ..
.\mvnw.cmd test
.\mvnw.cmd package
```

No Windows, o script abaixo compila o frontend, testa e abre o cliente:

```powershell
.\scripts\build-and-run.ps1 -HostAddress 127.0.0.1 -Port 5000 -Username isaac
```

## Executar

Servidor, aceitando conexões em todas as interfaces de rede:

```bash
.\mvnw.cmd exec:java "-Dexec.args=server --host 0.0.0.0 --port 5000"
```

Cliente gráfico:

```bash
.\mvnw.cmd javafx:run
```

Valores iniciais também podem ser fornecidos ao cliente:

```bash
.\mvnw.cmd javafx:run "-Djavafx.args=--host 192.168.1.20 --port 5000 --username isaac"
```

Na tela de entrada, o host e a porta continuam editáveis. `127.0.0.1` serve apenas para testes na mesma máquina; o código não fixa esse endereço para conexões reais.

## Uso

1. Inicie o servidor.
2. Abra dois ou mais clientes e informe nomes diferentes, com 3 a 20 letras, números ou `_`.
3. Selecione **Conversa geral** para broadcast ou um usuário para conversa privada.
4. Pressione Enter para enviar; Shift+Enter insere uma nova linha.
5. Em uma conversa privada, use `+` para escolher um arquivo de até 10 MB.
6. Arquivos recebidos são validados e gravados em `data/downloads`. O botão **Abrir** usa o aplicativo padrão do sistema.
7. Use **Desconectar** para o encerramento controlado.

## Teste entre máquinas na rede local

As máquinas devem estar na mesma rede. Na máquina servidora, descubra o IPv4:

```powershell
ipconfig
```

No Linux:

```bash
ip addr
```

Inicie o servidor com `--host 0.0.0.0`. Nos clientes, digite o IPv4 real da máquina servidora, por exemplo `192.168.1.20`, e a mesma porta. Não use `localhost`, pois ele sempre aponta para a própria máquina do cliente.

Se não conectar:

- confirme que servidor e cliente usam a mesma porta;
- teste a conectividade com `ping IP_DO_SERVIDOR`;
- verifique se a rede está marcada como privada/confiável;
- libere entrada TCP para a porta 5000 no firewall da máquina servidora;
- confirme que outro processo não está usando a porta;
- redes de convidados podem bloquear comunicação entre dispositivos.

Exemplo de regra temporária no Firewall do Windows, em terminal administrativo:

```powershell
New-NetFirewallRule -DisplayName "Chat TCP 5000" -Direction Inbound -Protocol TCP -LocalPort 5000 -Action Allow
```

Remova a regra após o teste caso ela não seja mais necessária.

## Testes automatizados

```bash
.\mvnw.cmd test
```

Os testes usam `127.0.0.1` e uma porta livre escolhida pelo sistema operacional. Eles validam framing JSON, login, duplicidade, broadcast, privado, lista, saídas, três clientes, recuperação após falha e transferência de arquivo. Os testes físicos entre máquinas devem ser registrados separadamente em [docs/testes.md](docs/testes.md).

## Documentação

- [Arquitetura](docs/arquitetura.md)
- [Protocolo](docs/protocolo.md)
- [Plano e evidências de testes](docs/testes.md)
- [Relatório técnico](docs/relatorio.md)
- [Guia de apresentação](docs/guia-de-apresentacao.md)
