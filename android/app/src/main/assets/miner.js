// Monero Miner - JavaScript Controller
var mining = false;
var statsTimer = null;
var startTime = null;
var totalShares = {accepted: 0, rejected: 0};

function $(id){ return document.getElementById(id); }

function log(msg, type){
    var cls = 'log-info';
    if(type === 'warn') cls = 'log-warn';
    else if(type === 'error') cls = 'log-error';
    else if(type === 'share') cls = 'log-share';
    
    var t = new Date().toLocaleTimeString();
    var line = '<div class="log-line"><span class="log-time">[' + t + ']</span> <span class="' + cls + '">' + msg + '</span></div>';
    $('logContainer').innerHTML += line;
    $('logContainer').scrollTop = $('logContainer').scrollHeight;
}

function updateStats(){
    if(!mining) return;
    
    // Call native bridge
    if(window.MinerBridge && window.MinerBridge.getStats){
        try {
            var stats = JSON.parse(window.MinerBridge.getStats());
            $('hashrateVal').textContent = Math.round(stats.hashrate);
            $('sharesAcc').textContent = stats.accepted || totalShares.accepted;
            $('sharesRej').textContent = stats.rejected || totalShares.rejected;
            
            if(startTime){
                var uptime = Math.floor((Date.now() - startTime) / 60000);
                $('uptimeVal').textContent = uptime + 'm';
            }
        } catch(e){}
    }
}

function startMining(){
    if(mining) return;
    
    var wallet = $('wallet').value.trim();
    if(!wallet || wallet.length < 90){
        log('Please enter a valid Monero wallet address', 'error');
        return;
    }
    
    var host = $('poolHost').value.trim() || 'pool.supportxmr.com';
    var port = parseInt($('poolPort').value) || 3333;
    var threads = parseInt($('threads').value) || 2;
    var worker = $('worker').value.trim() || 'android_miner';
    
    // Save config
    try {
        localStorage.setItem('miner_host', host);
        localStorage.setItem('miner_port', port);
        localStorage.setItem('miner_wallet', wallet);
        localStorage.setItem('miner_threads', threads);
        localStorage.setItem('miner_worker', worker);
    } catch(e){}
    
    mining = true;
    startTime = Date.now();
    totalShares = {accepted: 0, rejected: 0};
    
    $('statusText').textContent = 'MINING';
    $('statusText').className = 'status-value status-mining';
    $('btnStart').disabled = true;
    $('btnStop').disabled = false;
    
    log('Connecting to ' + host + ':' + port + '...');
    log('Wallet: ' + wallet.substring(0,8) + '...' + wallet.substring(wallet.length-6));
    log('Threads: ' + threads + ' | Worker: ' + worker);
    
    // Start native miner
    if(window.MinerBridge && window.MinerBridge.startMining){
        window.MinerBridge.startMining(host, port, wallet, worker, threads);
    } else {
        log('Native bridge not available - running in demo mode', 'warn');
    }
    
    // Stats polling
    statsTimer = setInterval(updateStats, 3000);
}

function stopMining(){
    if(!mining) return;
    mining = false;
    
    if(window.MinerBridge && window.MinerBridge.stopMining){
        window.MinerBridge.stopMining();
    }
    
    if(statsTimer){ clearInterval(statsTimer); statsTimer = null; }
    
    $('statusText').textContent = 'STOPPED';
    $('statusText').className = 'status-value status-ready';
    $('btnStart').disabled = false;
    $('btnStop').disabled = true;
    $('hashrateVal').textContent = '0';
    
    log('Miner stopped');
}

// Load saved config
(function(){
    try {
        var h = localStorage.getItem('miner_host');
        if(h) $('poolHost').value = h;
        var p = localStorage.getItem('miner_port');
        if(p) $('poolPort').value = p;
        var w = localStorage.getItem('miner_wallet');
        if(w) $('wallet').value = w;
        var t = localStorage.getItem('miner_threads');
        if(t) $('threads').value = t;
        var wk = localStorage.getItem('miner_worker');
        if(wk) $('worker').value = wk;
    } catch(e){}
    
    log('Monero RandomX Miner initialized');
    log('Ready to mine. Enter your wallet address and press START.');
})();
