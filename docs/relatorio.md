# Relatório técnico - Sistema de Chat Cliente-Servidor

## 1. Introdução

Este trabalho implementa comunicação em tempo real entre múltiplos clientes por meio de um servidor central. A solução usa sockets diretamente e define seu próprio protocolo de aplicação, tornando explícitos login, roteamento, presença, encerramento e transferência de arquivos.

## 2. Objetivo

O objetivo é demonstrar, em uma rede local real, conexão simultânea, identificação única, mensagens gerais e privadas, lista de usuários, tratamento de entrada/saída e envio seguro de arquivos. Host e porta são configuráveis para que cliente e servidor possam executar em máquinas diferentes.

## 3. Arquitetura

O servidor Java aceita sockets TCP e entrega cada conexão a uma tarefa independente. Um registro concorrente associa nomes normalizados às conexões. O cliente separa três responsabilidades: transporte em `TcpChatClient`, arquivos em `IncomingFileManager` e interface em JavaFX/React. A descrição e o diagrama completos estão em `arquitetura.md`.

## 4. Protocolo

O protocolo usa JSON UTF-8, versão 1 e uma quebra de linha como delimitador. Isso resolve o fato de TCP ser um fluxo de bytes sem fronteiras de mensagem. O servidor autentica primeiro e rejeita operações de sockets ainda não autenticados. Campos de remetente são criados pelo servidor a partir da conexão.

## 5. Justificativa do TCP

TCP foi escolhido porque oferece entrega confiável, preserva a ordem dos bytes, retransmite perdas e inclui controle de fluxo e congestionamento. Essas propriedades simplificam o chat e, principalmente, arquivos: o protocolo de aplicação ainda valida ordem, tamanho e hash, mas não precisa reinventar reconhecimento, retransmissão ou reordenação de datagramas. A pequena sobrecarga e a conexão persistente são adequadas ao volume e à latência esperados. UDP exigiria mecanismos adicionais para garantir integridade e ordem, aumentando muito a complexidade.

## 6. Concorrência

O `ExecutorService` atende cada cliente de forma independente. `ConcurrentHashMap.putIfAbsent` evita que duas conexões obtenham o mesmo nome em uma corrida. Cada saída possui lock próprio porque broadcast e mensagens privadas podem fazer threads distintas escreverem no mesmo socket. Nenhum lock global é mantido durante rede. A limpeza idempotente no `finally` remove usuário e transferências e então notifica os restantes.

## 7. Interface

A interface React apresenta login, estado da conexão, lista, conversa geral, privadas, eventos, erros e progresso de arquivos. O build Vite é carregado como recurso pelo JavaFX WebView, sem servidor HTTP. JavaScript chama a pequena `JavaScriptBridge`, e eventos recebidos voltam por um único `FrontendNotifier` na thread JavaFX.

## 8. Transferência de arquivos

Arquivos privados de até 10 MiB são lidos incrementalmente em blocos de 24 KiB. Cada transferência tem UUID. O servidor verifica destinatário, sequência, bytes e SHA-256 enquanto encaminha. O receptor repete a validação, grava em `.part`, escolhe um nome livre para evitar sobrescrita e só move para o nome final após sucesso. Temporários incompletos são excluídos.

Base64 aumenta o volume transmitido em aproximadamente um terço, mas mantém todo o protocolo em linhas JSON e facilita sua explicação e inspeção. O conteúdo Base64 não aparece no histórico nem nos logs.

## 9. Testes

Foram executados 12 testes automáticos com JUnit 5 por `mvnw.cmd test`, com 0 falhas e 0 erros no ambiente de desenvolvimento em 03/08/2026. Eles abrangem protocolo, login, roteamento, ciclo de vida, três clientes, resiliência e arquivos. O teste físico entre máquinas ainda deve ser registrado no espaço próprio de `testes.md`; nenhum resultado de laboratório foi presumido.

## 10. Dificuldades reais

As principais dificuldades de projeto foram delimitar mensagens sobre um fluxo TCP, impedir escritas concorrentes misturadas, evitar duplicidade de nomes durante logins simultâneos e garantir limpeza única em diferentes formas de desconexão. Na transferência, foi necessário manter estado incremental nos três participantes e garantir que falhas removam `.part` sem afetar o loop principal do chat.

## 11. Limitações

- estado somente em memória, sem histórico persistente;
- sem criptografia TLS ou autenticação por senha;
- arquivos não retomam após queda da conexão;
- Base64 consome mais banda que um canal binário;
- limite de arquivo e tamanho de mensagem são fixos no código por padrão;
- o registro de teste em rede física depende do laboratório.

Essas limitações preservam o foco didático em sockets, protocolo, concorrência e confiabilidade.

## 12. Conclusão

A solução implementa os requisitos funcionais com componentes pequenos e responsabilidades explícitas. TCP fornece a base confiável, o protocolo JSON estabelece as operações e validações, e a separação entre servidor, cliente, arquivos e UI mantém o código compreensível para apresentação e evolução.
