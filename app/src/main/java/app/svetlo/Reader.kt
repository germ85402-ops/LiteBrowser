package app.svetlo

import android.text.Html
import org.json.JSONObject

class Article(val url: String, val title: String, val byline: String?, val site: String?, val html: String, val lang: String?)

/** Readability-style article extraction running in the page, plus the reader page template. */
object Reader {
    /** Minimum extracted text length to treat the page as an article. */
    const val MIN_TEXT = 400

    /** Returns JSON {title, byline, site, lang, html, len} or null. Works on a clone; the page is untouched. */
    const val EXTRACT_JS = """(function(){try{
function tx(e){return (e.textContent||'').replace(/\s+/g,' ').trim()}
function cls(e){var c=e.className;return ((c&&c.baseVal!==undefined)?c.baseVal:(c||''))+' '+(e.id||'')}
var NEG=/comment|meta|footer|footnote|sidebar|widget|nav|menu|share|social|related|recommend|promo|advert|\bads?\b|banner|sponsor|subscribe|newsletter|popup|modal|cookie|breadcrumb|tags|masthead|toolbar|pagination/i,
POS=/article|body|content|entry|main|page|post|text|blog|story|hentry|lead/i;
function w(e){var s=cls(e),v=0;if(NEG.test(s))v-=25;if(POS.test(s))v+=25;return v}
var d=document.cloneNode(true),b=d.body;if(!b)return null;
b.querySelectorAll('script,style,noscript,iframe,object,embed,form,button,input,select,textarea,svg,canvas,nav,footer,aside,dialog,[hidden],[aria-hidden=true],[role=navigation],[role=banner],[role=complementary]').forEach(function(e){e.remove()});
b.querySelectorAll('header').forEach(function(e){if(!e.closest('article'))e.remove()});
b.querySelectorAll('img').forEach(function(i){var s=i.getAttribute('data-src')||i.getAttribute('data-lazy-src')||i.getAttribute('data-original')||i.getAttribute('src')||'';
if(!s||/^data:/.test(s)){var ss=(i.getAttribute('srcset')||i.getAttribute('data-srcset')||'').split(',').pop();s=ss?ss.trim().split(' ')[0]:s}
try{if(!s)throw 0;i.setAttribute('src',new URL(s,location.href).href)}catch(e){i.remove()}});
var sc=new Map();function add(e,v){if(!e||e===b.parentNode)return;if(!sc.has(e))sc.set(e,w(e)+(/^(ARTICLE|MAIN)$/.test(e.tagName)?25:e.tagName==='DIV'?5:0));sc.set(e,sc.get(e)+v)}
b.querySelectorAll('p,pre,td,blockquote').forEach(function(p){var t=tx(p);if(t.length<25)return;var s=1+t.split(/[,，、]/).length+Math.min(3,Math.floor(t.length/100));
var q=p.parentElement;add(q,s);if(q)add(q.parentElement,s/2)});
function ld(e){var l=0,t=tx(e).length;e.querySelectorAll('a').forEach(function(a){l+=tx(a).length});return t?l/t:1}
var top=null,best=0;sc.forEach(function(s,e){s*=1-ld(e);if(s>best){best=s;top=e}});if(!top)return null;
var out=d.createElement('div'),th=Math.max(10,best*0.2),par=top.parentElement,sib=par?Array.prototype.slice.call(par.children):[top];
sib.forEach(function(s){var ok=s===top;if(!ok&&sc.has(s)&&sc.get(s)*(1-ld(s))>=th)ok=true;if(!ok&&s.tagName==='P'){var t=tx(s);ok=t.length>80&&ld(s)<0.25}if(ok)out.appendChild(s)});
out.querySelectorAll('div,section,table,ul,ol,figure').forEach(function(e){if(!e.isConnected&&!out.contains(e))return;var t=tx(e).length,l=ld(e),im=e.querySelectorAll('img').length;
if((w(e)<0&&l>0.2)||(l>0.5&&t<1000)||(t<25&&im===0&&!e.querySelector('pre,code,video')))e.remove()});
var keep={src:1,href:1,alt:1,title:1,colspan:1,rowspan:1};
out.querySelectorAll('*').forEach(function(e){Array.prototype.slice.call(e.attributes).forEach(function(a){if(!keep[a.name])e.removeAttribute(a.name)});
if(e.tagName==='A'){var h=e.getAttribute('href')||'';if(/^\s*javascript:/i.test(h))e.removeAttribute('href');else try{e.setAttribute('href',new URL(h,location.href).href)}catch(x){}}});
function meta(n){var m=document.querySelector('meta[property="'+n+'"],meta[name="'+n+'"]');return m?m.getAttribute('content'):null}
var h1=document.querySelector('h1'),title=meta('og:title')||(h1?tx(h1):'')||document.title;
out.querySelectorAll('h1').forEach(function(h){if(tx(h)===title)h.remove()});
return JSON.stringify({title:title,byline:meta('author')||meta('article:author'),site:meta('og:site_name'),lang:document.documentElement.lang||null,html:out.innerHTML,len:tx(out).length});
}catch(e){return null}})()"""

    fun parse(url: String, json: String?): Article? {
        val o = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
        if (o.optInt("len") < MIN_TEXT) return null
        fun str(k: String) = o.optString(k).takeIf { !o.isNull(k) && it.isNotBlank() }
        return Article(url, str("title") ?: url, str("byline"), str("site"), o.optString("html"), str("lang"))
    }

    enum class Theme(val bg: String, val fg: String, val muted: String, val link: String) {
        LIGHT("#FFFFFF", "#1B1C1F", "#6B7280", "#3A67F0"),
        SEPIA("#F6EFDF", "#4B3B2A", "#8A7560", "#9A5B13"),
        DARK("#15171C", "#E3E5EA", "#9AA0AA", "#8EABFF"),
    }

    private fun esc(s: String) = Html.escapeHtml(s)

    fun page(a: Article, theme: Theme, fontPx: Int, serif: Boolean): String {
        val family = if (serif) "Georgia,'Noto Serif',serif" else "system-ui,Roboto,sans-serif"
        val meta = listOfNotNull(a.site, a.byline).joinToString(" · ")
        return """<!doctype html><html lang="${esc(a.lang ?: "")}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="script-src 'none'; object-src 'none'">
<style>
html{background:${theme.bg}}body{margin:0;padding:20px 20px 72px;color:${theme.fg};font:${fontPx}px/1.65 $family;overflow-wrap:break-word}
main{max-width:680px;margin:0 auto}.m{color:${theme.muted};font:13px/1.4 system-ui,sans-serif;margin:0 0 10px}
h1.t{font-size:1.55em;line-height:1.25;margin:0 0 24px}h2,h3{line-height:1.3}
img,video{max-width:100%;height:auto;border-radius:6px}figure{margin:20px 0}figcaption{color:${theme.muted};font-size:.85em}
a{color:${theme.link}}pre{overflow-x:auto;background:rgba(127,127,127,.12);padding:12px;border-radius:8px;font-size:.85em}
code{font-size:.9em}blockquote{margin:18px 0;padding-left:16px;border-left:3px solid ${theme.muted};color:${theme.muted}}
table{border-collapse:collapse;display:block;overflow-x:auto}td,th{border:1px solid rgba(127,127,127,.3);padding:4px 8px}
</style></head><body><main>${if (meta.isNotEmpty()) "<p class=\"m\">${esc(meta)}</p>" else ""}<h1 class="t">${esc(a.title)}</h1>
${a.html}</main></body></html>"""
    }
}
