# Testes

## Automáticos

Execute:

```bash
.\mvnw.cmd test
```

A suíte usa loopback e uma porta livre do sistema, sem fixar a porta do laboratório. Ela cobre:

| Área | Verificações |
|---|---|
| Protocolo | serialização, desserialização, framing por `\n`, versão e tipo obrigatórios |
| Login | nome válido e duplicidade sem diferenciar caixa |
| Chat | broadcast, privado, destino inexistente e lista ordenada |
| Ciclo de vida | saída normal, reset inesperado, remoção de fantasmas |
| Concorrência | três conexões iniciadas por tarefas concorrentes |
| Resiliência | servidor continua roteando após falha de um cliente |
| Arquivos | divisão em blocos, reconstrução, SHA-256, ordem, limite e nome inseguro |
| Integração de arquivo | `FILE_START`, bloco, `FILE_END` e confirmação entre dois sockets |

Resultado obtido no ambiente de desenvolvimento em 03/08/2026: 12 testes executados, 0 falhas e 0 erros. Esse resultado não substitui o teste físico em rede local.

## Roteiro manual local

1. Inicie o servidor em `0.0.0.0:5000`.
2. Abra três clientes com nomes distintos.
3. Tente um quarto cliente com nome igual, mudando apenas maiúsculas.
4. Envie broadcast e confirme a entrega nos três clientes.
5. Envie uma mensagem privada e confirme que o terceiro cliente não a vê.
6. Envie um arquivo, abra o recebido e compare o SHA-256 se desejado.
7. Feche um cliente pelo botão e outro encerrando a janela/processo.
8. Confirme que a lista é atualizada e o chat continua ativo.

## Registro do teste entre máquinas físicas

Preencher no laboratório, sem presumir resultados:

| Item | Registro |
|---|---|
| Data e local | |
| Máquina servidora / sistema | |
| IPv4 do servidor | |
| Porta TCP | |
| Clientes / sistemas | |
| Regra de firewall necessária | |
| Broadcast com 3 clientes | |
| Mensagem privada | |
| Arquivo enviado (nome/tamanho/hash) | |
| Desconexão inesperada | |
| Problemas encontrados e correções | |
