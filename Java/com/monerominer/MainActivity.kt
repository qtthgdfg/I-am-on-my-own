package com.monerominer

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    
    private lateinit var webView: WebView
    
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
            }
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(MinerBridge(), "MinerBridge")
            loadUrl("file:///android_asset/index.html")
        }
        
        setContentView(webView)
        
        // Load native library
        try {
            System.loadLibrary("monerominer")
        } catch (e: UnsatisfiedLinkError) {
            // Will be loaded by bridge when needed
        }
    }
    
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
    
    inner class MinerBridge {
        @JavascriptInterface
        fun startMining(host: String, port: Int, wallet: String, worker: String, threads: Int): Boolean {
            return nativeStartMining(host, port, wallet, worker, threads)
        }
        
        @JavascriptInterface
        fun stopMining() {
            nativeStopMining()
        }
        
        @JavascriptInterface
        fun getStats(): String {
            return nativeGetStats()
        }
    }
    
    private external fun nativeStartMining(host: String, port: Int, wallet: String, worker: String, threads: Int): Boolean
    private external fun nativeStopMining()
    private external fun nativeGetStats(): String
}
