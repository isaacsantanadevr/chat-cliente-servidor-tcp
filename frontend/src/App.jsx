import { useEffect, useMemo, useRef, useState } from 'react'

const bridge = () => window.javaBridge
const nowLabel = timestamp => timestamp
  ? new Date(timestamp).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })
  : new Date().toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })

export default function App() {
  const [form, setForm] = useState({ host: '127.0.0.1', port: '5000', username: '' })
  const [status, setStatus] = useState('offline')
  const [currentUser, setCurrentUser] = useState('')
  const [users, setUsers] = useState([])
  const [selected, setSelected] = useState(null)
  const [messages, setMessages] = useState([])
  const [files, setFiles] = useState({})
  const [draft, setDraft] = useState('')
  const [loginError, setLoginError] = useState('')
  const endRef = useRef(null)

  useEffect(() => {
    window.setConnectionDefaults = defaults => setForm({
      host: defaults.host || '127.0.0.1', port: String(defaults.port || 5000), username: defaults.username || ''
    })
    window.receiveChatEvent = event => handleEvent(event)
    return () => {
      delete window.setConnectionDefaults
      delete window.receiveChatEvent
    }
  }, [currentUser, status])

  useEffect(() => endRef.current?.scrollIntoView({ behavior: 'smooth' }), [messages, files, selected])

  function handleEvent(event) {
    if (event.type === 'LOGIN_OK') {
      setCurrentUser(event.username)
      setStatus('online')
      setLoginError('')
      addSystem(`Conectado como ${event.username}`)
    } else if (event.type === 'USER_LIST') {
      setUsers(event.users || [])
      setSelected(previous => previous && event.users?.some(user => user.toLowerCase() === previous.toLowerCase()) ? previous : null)
    } else if (event.type === 'CHAT_MESSAGE') {
      setMessages(previous => [...previous, { ...event, kind: 'chat' }])
    } else if (event.type === 'USER_JOINED') {
      addSystem(`${event.username} entrou no chat`, event.timestamp)
    } else if (event.type === 'USER_LEFT') {
      addSystem(`${event.username} saiu do chat`, event.timestamp)
    } else if (event.type === 'FILE_START' || event.type === 'FILE_PROGRESS' || event.type === 'FILE_RECEIVED') {
      setFiles(previous => ({
        ...previous,
        [event.transferId]: { ...(previous[event.transferId] || {}), ...event }
      }))
    } else if (event.type === 'ERROR') {
      if (status !== 'online') setLoginError(event.error || 'Não foi possível conectar')
      setMessages(previous => [...previous, { kind: 'error', content: event.error, timestamp: new Date().toISOString() }])
      if (event.transferId) setFiles(previous => ({
        ...previous, [event.transferId]: { ...(previous[event.transferId] || {}), status: 'ERROR', error: event.error }
      }))
      if (status === 'connecting') setStatus('offline')
    } else if (event.type === 'BYE') {
      setStatus('offline')
      setUsers([])
      setSelected(null)
      if (currentUser) addSystem(event.content || 'Desconectado do servidor')
      setCurrentUser('')
    }
  }

  function addSystem(content, timestamp = new Date().toISOString()) {
    setMessages(previous => [...previous, { kind: 'system', content, timestamp }])
  }

  function connect(event) {
    event.preventDefault()
    setLoginError('')
    const port = Number(form.port)
    if (!form.host.trim() || !form.username.trim() || !Number.isInteger(port) || port < 1 || port > 65535) {
      setLoginError('Preencha usuário, endereço e uma porta válida.')
      return
    }
    setStatus('connecting')
    bridge()?.connect(form.host.trim(), port, form.username.trim())
  }

  function send() {
    const content = draft.trim()
    if (!content) return
    if (selected) bridge()?.sendPrivateMessage(selected, content)
    else bridge()?.sendBroadcast(content)
    setDraft('')
  }

  function keyDown(event) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      send()
    }
  }

  const visibleMessages = useMemo(() => messages.filter(message => {
    if (message.kind !== 'chat') return !selected
    if (!selected) return message.scope === 'BROADCAST'
    return message.scope === 'PRIVATE' && [message.from, message.to]
      .some(name => name?.toLowerCase() === selected.toLowerCase())
  }), [messages, selected])

  const visibleFiles = Object.values(files).filter(file => !selected ||
    [file.from, file.to].some(name => name?.toLowerCase() === selected.toLowerCase()))

  if (status !== 'online') return (
    <main className="login-shell">
      <section className="login-card">
        <div className="brand-mark">C</div>
        <p className="eyebrow">REDES DE COMPUTADORES 2</p>
        <h1>Conecta</h1>
        <p className="lead">Mensagens e arquivos pela rede local, com comunicação TCP direta.</p>
        <form onSubmit={connect}>
          <label>Nome de usuário<input value={form.username} maxLength="20" autoFocus
            onChange={e => setForm({ ...form, username: e.target.value })} placeholder="ex.: isaac_01" /></label>
          <div className="form-row">
            <label>Servidor<input value={form.host} onChange={e => setForm({ ...form, host: e.target.value })} /></label>
            <label className="port-field">Porta<input value={form.port} inputMode="numeric"
              onChange={e => setForm({ ...form, port: e.target.value })} /></label>
          </div>
          {loginError && <div className="login-error" role="alert">{loginError}</div>}
          <button className="primary wide" disabled={status === 'connecting'}>
            {status === 'connecting' ? <><span className="spinner" />Conectando...</> : 'Entrar no chat'}
          </button>
        </form>
        <p className="hint">Use o IPv4 da máquina servidora quando estiver em outro computador.</p>
      </section>
    </main>
  )

  return (
    <main className="app-shell">
      <header className="topbar">
        <div className="brand-inline"><span className="brand-mark small">C</span><div><strong>Conecta</strong><small>Chat TCP</small></div></div>
        <div className="session"><span className="online-dot" /><div><strong>{currentUser}</strong><small>Conectado a {form.host}:{form.port}</small></div>
          <button className="ghost" onClick={() => bridge()?.disconnect()}>Desconectar</button></div>
      </header>
      <div className="workspace">
        <aside className="sidebar">
          <button className={`conversation ${selected === null ? 'active' : ''}`} onClick={() => setSelected(null)}>
            <span className="avatar general">#</span><span><strong>Conversa geral</strong><small>Todos os participantes</small></span>
          </button>
          <div className="sidebar-title"><span>PESSOAS</span><span>{users.length}</span></div>
          <div className="user-list">
            {users.filter(user => user.toLowerCase() !== currentUser.toLowerCase()).map(user => (
              <button key={user} className={`conversation ${selected === user ? 'active' : ''}`} onClick={() => setSelected(user)}>
                <span className="avatar">{user[0].toUpperCase()}</span><span><strong>{user}</strong><small><i className="mini-dot" /> disponível</small></span>
              </button>
            ))}
            {users.length <= 1 && <p className="empty-users">Aguardando outras pessoas...</p>}
          </div>
        </aside>
        <section className="chat-panel">
          <div className="chat-heading"><div><h2>{selected || 'Conversa geral'}</h2><p>{selected ? 'Conversa privada' : `${users.length} participante${users.length === 1 ? '' : 's'} conectado${users.length === 1 ? '' : 's'}`}</p></div>
            {selected && <span className="private-badge">Privado</span>}</div>
          <div className="history">
            {visibleMessages.length === 0 && visibleFiles.length === 0 && <div className="empty-state"><div>•••</div><h3>Comece a conversa</h3><p>{selected ? `Envie uma mensagem privada para ${selected}.` : 'As mensagens para todos aparecerão aqui.'}</p></div>}
            {visibleMessages.map((message, index) => <MessageItem key={`${message.timestamp}-${index}`} message={message} own={message.from === currentUser} />)}
            {visibleFiles.map(file => <FileItem key={file.transferId} file={file} />)}
            <div ref={endRef} />
          </div>
          <div className="composer">
            <textarea value={draft} onChange={e => setDraft(e.target.value)} onKeyDown={keyDown}
              placeholder={selected ? `Mensagem privada para ${selected}` : 'Mensagem para todos'} rows="1" />
            <button className="icon-button" title={selected ? 'Selecionar arquivo' : 'Selecione uma pessoa para enviar arquivo'}
              disabled={!selected} onClick={() => bridge()?.chooseAndSendFile(selected)}>＋</button>
            <button className="send-button" disabled={!draft.trim()} onClick={send}>Enviar <span>↗</span></button>
          </div>
          <div className="composer-hint">Enter para enviar · Shift+Enter para quebrar linha {selected && '· Arquivos até 10 MB'}</div>
        </section>
      </div>
    </main>
  )
}

function MessageItem({ message, own }) {
  if (message.kind === 'system') return <div className="system-message"><span>{message.content}</span><time>{nowLabel(message.timestamp)}</time></div>
  if (message.kind === 'error') return <div className="error-message">{message.content}</div>
  return <article className={`message ${own ? 'own' : ''} ${message.scope === 'PRIVATE' ? 'private' : ''}`}>
    <div className="message-meta"><strong>{own ? 'Você' : message.from}</strong>{message.scope === 'PRIVATE' && <span>privada</span>}<time>{nowLabel(message.timestamp)}</time></div>
    <p>{message.content}</p>
  </article>
}

function FileItem({ file }) {
  const progress = file.progress ?? (file.status === 'COMPLETED' ? 100 : 0)
  const completed = file.status === 'COMPLETED'
  return <article className={`file-card ${file.status === 'ERROR' ? 'failed' : ''}`}>
    <div className="file-icon">▤</div><div className="file-info"><strong>{file.fileName || 'Arquivo'}</strong>
      <span>{formatSize(file.fileSize)} {file.to ? `· para ${file.to}` : ''}</span>
      <div className="progress"><i style={{ width: `${progress}%` }} /></div>
      <small>{file.status === 'ERROR' ? file.error : completed ? 'Transferência concluída' : `${progress}% · transferindo`}</small>
    </div>
    {completed && file.path && <button className="ghost compact" onClick={() => bridge()?.openFile(file.path)}>Abrir</button>}
  </article>
}

function formatSize(bytes) {
  if (bytes == null) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
