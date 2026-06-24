/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.niap.strictbrowser

import android.app.Activity
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var webView: WebView

    private val testSites = listOf(
        "https://csrc.nist.gov" to "NIST CSRC",
        "https://www.whitehouse.gov" to "White House",
        "https://www.google.com" to "Google",
        "https://www.wikipedia.org" to "Wikipedia (Fail check)",
        "https://x.com" to "x.com (Out of bounds - should apply base setting)",
        "https://example.com" to "Example.com (Out of bounds - should apply base setting)"

    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        val errorHtml = """
                            <html>
                            <body style="padding: 20px; font-family: sans-serif; background-color: #FFF0F0; color: #D8000C;">
                                <h1 style="font-size: 20px; margin-bottom: 10px;">Connection Blocked</h1>
                                <p style="font-size: 14px; line-height: 1.5;">
                                    <b>Error Code:</b> ${error.errorCode}<br/>
                                    <b>Description:</b> ${error.description}<br/>
                                    <b>Target URL:</b> ${request.url}
                                </p>
                            </body>
                            </html>
                        """.trimIndent()
                        view.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
                    } else {
                        android.util.Log.w("StrictBrowser", "Subresource load error: ${request.url}")
                    }
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: android.webkit.SslErrorHandler,
                    error: android.net.http.SslError
                ) {
                    val requestUrl = error.url
                    val mainUrl = view.url
                    val isMainFrame = mainUrl == null || requestUrl.startsWith(mainUrl) || mainUrl.startsWith(requestUrl)

                    if (isMainFrame) {
                        val errorHtml = """
                            <html>
                            <body style="padding: 20px; font-family: sans-serif; background-color: #FFF0F0; color: #D8000C;">
                                <h1 style="font-size: 20px; margin-bottom: 10px;">SSL Handshake Blocked (NIAP Violation)</h1>
                                <p style="font-size: 14px; line-height: 1.5;">
                                    <b>Primary Ssl Error Code:</b> ${error.primaryError}<br/>
                                    <b>Certificate:</b> ${error.certificate}<br/>
                                    <b>Target URL:</b> ${error.url}
                                </p>
                            </body>
                            </html>
                        """.trimIndent()
                        view.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
                        handler.cancel()
                    } else {
                        android.util.Log.w("StrictBrowser", "Blocking subresource due to SSL Error: $requestUrl")
                        handler.cancel()
                    }
                }
            }
        }
        setContentView(webView)
        
        // load google.com first and it should work by domain-config
        webView.loadUrl("https://google.com")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        testSites.forEachIndexed { index, pair ->
            menu.add(Menu.NONE, index, Menu.NONE, pair.second)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val url = testSites[item.itemId].first
        webView.loadUrl(url)
        return true
    }
}
