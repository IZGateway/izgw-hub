/**
 * Patches Node.js http/https to enable TCP keepalive on every socket.
 *
 * GitHub Actions runners sit behind Azure SNAT, which drops TCP connections
 * that appear idle for ~38 seconds. Long-running IZG Hub requests (e.g. TC_13c,
 * which waits for a downstream IIS connect timeout) trigger this drop before the
 * response arrives, causing Newman to report ETIMEDOUT.
 *
 * SO_KEEPALIVE is off by default in Node.js. This script enables it with a short
 * idle threshold (15s) so the OS sends TCP keepalive probes before the NAT
 * session expires. Combined with the sysctl settings in the workflow
 * (tcp_keepalive_intvl, tcp_keepalive_probes), probes keep the NAT entry alive
 * for the duration of the request.
 *
 * Load via: NODE_OPTIONS="--require ./.github/scripts/enable-keepalive.js"
 */
'use strict';

const http = require('http');
const https = require('https');

const KEEPALIVE_IDLE_MS = 15000; // start probes after 15s idle

function attachKeepAlive(req) {
    req.on('socket', (socket) => {
        socket.setKeepAlive(true, KEEPALIVE_IDLE_MS);
    });
}

const origHttpRequest = http.request.bind(http);
http.request = function (options, callback) {
    const req = origHttpRequest(options, callback);
    attachKeepAlive(req);
    return req;
};

const origHttpsRequest = https.request.bind(https);
https.request = function (options, callback) {
    const req = origHttpsRequest(options, callback);
    attachKeepAlive(req);
    return req;
};
