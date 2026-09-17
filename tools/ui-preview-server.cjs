// 极简静态预览服务器：仅服务 ui-preview.html，零依赖（Node 内置 http）
const http = require('http');
const fs = require('fs');
const path = require('path');
const file = path.join(__dirname, 'ui-preview.html');
http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  fs.createReadStream(file).pipe(res);
}).listen(4173, () => console.log('UI preview at http://127.0.0.1:4173'));
