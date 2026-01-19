
import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';

// #region agent log
fetch('http://127.0.0.1:7242/ingest/89dc02bd-def5-4814-a81b-220fad0c0c0e',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({location:'index.tsx:1',message:'App startup - imports loaded',data:{},timestamp:Date.now(),sessionId:'debug-session',runId:'initial',hypothesisId:'H1'})}).catch(()=>{});
// #endregion

const rootElement = document.getElementById('root');
// #region agent log
fetch('http://127.0.0.1:7242/ingest/89dc02bd-def5-4814-a81b-220fad0c0c0e',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({location:'index.tsx:6',message:'Root element check',data:{rootElementFound:!!rootElement},timestamp:Date.now(),sessionId:'debug-session',runId:'initial',hypothesisId:'H1'})}).catch(()=>{});
// #endregion
if (!rootElement) {
  throw new Error("Could not find root element to mount to");
}

const root = ReactDOM.createRoot(rootElement);
// #region agent log
fetch('http://127.0.0.1:7242/ingest/89dc02bd-def5-4814-a81b-220fad0c0c0e',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({location:'index.tsx:11',message:'React root created, about to render',data:{},timestamp:Date.now(),sessionId:'debug-session',runId:'initial',hypothesisId:'H1'})}).catch(()=>{});
// #endregion
root.render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
