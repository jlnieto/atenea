"use strict";

const http = require("http");

http.createServer((request, response) => {
  const ready = request.url === "/" || request.url === "/health";
  response.writeHead(ready ? 200 : 404, {"content-type": "application/json"});
  response.end(JSON.stringify({
    fixture: "dummy-compose",
    status: ready ? "UP" : "NOT_FOUND"
  }));
}).listen(8080, "0.0.0.0");
