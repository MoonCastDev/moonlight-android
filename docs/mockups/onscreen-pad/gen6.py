import math
exec(open('gen5.py').read().split('# ---- upright')[0])     # scene, game_svg, wordmark, defs
A=0.84   # how solid the controls are (Delta-like: readable, the game shows through a little)
def T6(x,y,s,t,fill='#fff',w=800): return f'<text x="{x:.1f}" y="{y:.1f}" font-family="Figtree" font-weight="{w}" font-size="{s:.1f}" fill="{fill}" text-anchor="middle" dominant-baseline="central">{t}</text>'
OUT='rgba(255,255,255,.85)'
def sh(): return 'filter="url(#sh6)"'
DEF6='<filter id="sh6" x="-20%" y="-20%" width="140%" height="140%"><feDropShadow dx="0" dy="4" stdDeviation="5" flood-color="#000" flood-opacity=".35"/></filter>'
def fill(c):   # flat colour with a soft top light, like moulded plastic seen flat
    g=f'f{c[1:]}'
    return g, f'<linearGradient id="{g}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{shade(c,1.18)}"/><stop offset="1" stop-color="{shade(c,.86)}"/></linearGradient>'
def pill6(x,y,w,h,c,label,lc='#fff',ls=None,rot=0):
    g,d=fill(c)
    return f'<defs>{d}</defs><g transform="rotate({rot} {x} {y})"><rect x="{x-w/2}" y="{y-h/2}" width="{w}" height="{h}" rx="{h/2}" fill="url(#{g})" stroke="{OUT}" stroke-width="4" {sh()}/>'+T6(x,y,ls or h*.46,label,lc)+'</g>'
def btn6(x,y,r,c,label,lc='#fff'):
    g,d=fill(c)
    return f'<defs>{d}</defs><circle cx="{x}" cy="{y}" r="{r}" fill="url(#{g})" stroke="{OUT}" stroke-width="4" {sh()}/>'+T6(x,y+r*.03,r*.78,label,lc)
def combo6(x,y,r,c,label):   # Delta's both-at-once button: a curved wedge under the pair
    g,d=fill(c)
    p=f'M{x-r} {y+r*.55} L{x+r*.95} {y+r*.55} Q{x+r*1.15} {y-r*.9} {x+r*.65} {y-r*.95} Q{x+r*.2} {y-r*.1} {x-r*.75} {y-r*.15} Z'
    return f'<defs>{d}</defs><path d="{p}" fill="url(#{g})" stroke="{OUT}" stroke-width="4" stroke-linejoin="round" {sh()}/>'+T6(x+r*.08,y+r*.12,r*.55,label)
def dpad6(x,y,r,c,ac='#fff'):
    g,d=fill(c); a=r*.36; k=a*.45
    p=f'M{x-a} {y-r+k} Q{x-a} {y-r} {x-a+k} {y-r} H{x+a-k} Q{x+a} {y-r} {x+a} {y-r+k} V{y-a} H{x+r-k} Q{x+r} {y-a} {x+r} {y-a+k} V{y+a-k} Q{x+r} {y+a} {x+r-k} {y+a} H{x+a} V{y+r-k} Q{x+a} {y+r} {x+a-k} {y+r} H{x-a+k} Q{x-a} {y+r} {x-a} {y+r-k} V{y+a} H{x-r+k} Q{x-r} {y+a} {x-r} {y+a-k} V{y-a+k} Q{x-r} {y-a} {x-r+k} {y-a} H{x-a} Z'
    ch=lambda rot: f'<path d="M{x-r*.13} {y-r*.62} L{x} {y-r*.76} L{x+r*.13} {y-r*.62}" fill="none" stroke="{ac}" stroke-width="{r*.06}" stroke-linecap="round" stroke-linejoin="round" transform="rotate({rot} {x} {y})"/>'
    return (f'<defs>{d}</defs><path d="{p}" fill="url(#{g})" stroke="{OUT}" stroke-width="4" {sh()}/>'+''.join(ch(k_) for k_ in (0,90,180,270))
            +f'<circle cx="{x}" cy="{y}" r="{a*.62}" fill="none" stroke="{ac}" stroke-width="{r*.05}"/>')
def stick6(x,y,r,c):
    g,d=fill(c)
    return f'<defs>{d}</defs><circle cx="{x}" cy="{y}" r="{r}" fill="{c}" fill-opacity=".45" stroke="{OUT}" stroke-width="4"/><circle cx="{x}" cy="{y}" r="{r*.55}" fill="url(#{g})" stroke="{OUT}" stroke-width="4" {sh()}/>'
def cbtn6(x,y,r):
    out=''
    for ang,(dx,dy) in zip([0,180,-90,90],[(0,-1),(0,1),(-1,0),(1,0)]):
        px,py=x+dx*r*.62,y+dy*r*.62
        out+=btn6(px,py,r*.34,'#E8B417','')+f'<path d="M{px} {py-r*.12}l{-r*.11} {r*.16}h{r*.22}z" fill="#3a2a00" transform="rotate({ang} {px} {py})"/>'
    return out
# Delta's landscape positions, as fractions of the screen (measured from its GBA skin)
def P(fx,fy): return (fx*W, fy*H)
def frame6(body, style_caption):
    gw=int(H*4/3); gx=(W-gw)//2
    o=f'<svg width="{W}" height="{H}" viewBox="0 0 {W} {H}"><defs>{DEFS}{DEF5}{DEF6}</defs><rect width="{W}" height="{H}" fill="#000"/>'+game_svg(gx,0,gw,H)
    o+=f'<g opacity="{A}">{body}</g></svg>'
    cap=f'<div style="position:absolute;left:50%;transform:translateX(-50%);top:150px;font:700 25px Figtree;color:#fff;background:rgba(0,0,0,.6);padding:8px 18px;border-radius:12px;white-space:nowrap">{style_caption}</div>'
    return f'<div style="position:relative;width:{W}px;height:{H}px">{o}{cap}</div>'
LR=lambda c,lc='#fff': pill6(*P(.095,.10),0.13*W,0.085*H,c,'L',lc)+pill6(*P(.905,.10),0.13*W,0.085*H,c,'R',lc)
MENU=lambda c: pill6(*P(.5,.075),0.085*W,0.058*H,c,'MENU',ls=0.026*H)
SS=lambda c,lc='#fff': pill6(*P(.445,.925),0.085*W,0.058*H,c,'SELECT',lc,0.025*H)+pill6(*P(.555,.925),0.085*W,0.058*H,c,'START',lc,0.025*H)
styles={
 'SNES':(LR('#8E8F99')+MENU('#55565e')+SS('#7d7e88')+dpad6(*P(.125,.70),0.165*H,'#2b2b30')
        +btn6(*P(.855,.565),0.072*H,'#2E5FC9','X')+btn6(*P(.92,.70),0.072*H,'#D8382E','A')+btn6(*P(.855,.835),0.072*H,'#E9C433','B','#4a3a00')+btn6(*P(.79,.70),0.072*H,'#2E9D52','Y'),
        'SNES: grey L R, black cross, the four colours as a diamond'),
 'NES':(MENU('#2a2a2e')+SS('#2a2a2e','#c9c9cf')+dpad6(*P(.125,.70),0.165*H,'#222226')
        +btn6(*P(.895,.60),0.085*H,'#C42E36','A')+btn6(*P(.80,.75),0.085*H,'#C42E36','B')+combo6(*P(.895,.84),0.085*H,'#9e2229','AB'),
        'NES: black cross, red A and B, and Delta\'s AB button to press both'),
 'N64':(LR('#6B4FB8')+pill6(*P(.095,.215),0.10*W,0.075*H,'#4B3590','Z')+MENU('#4B3590')+pill6(*P(.555,.925),0.085*W,0.058*H,'#C8302E','START',ls=0.025*H)
        +stick6(*P(.125,.66),0.16*H,'#6B4FB8')+dpad6(*P(.255,.86),0.085*H,'#4B3590')
        +btn6(*P(.88,.52),0.075*H,'#2D57C2','A')+btn6(*P(.80,.42),0.065*H,'#2B9A47','B')+cbtn6(*P(.855,.78),0.15*H),
        'N64: purple like Delta\'s skin, the stick and a small cross, blue A, green B, yellow C buttons, red Start'),
}
css='@font-face{font-family:Figtree;src:url(Figtree.ttf);font-weight:100 900}@font-face{font-family:Unbounded;src:url(Unbounded.ttf);font-weight:100 900}html,body{margin:0;overflow:hidden;background:#000}'
for n,(b,cp) in styles.items():
    open(f'v6_{n}.html','w').write(f'<html><head><style>{css}</style></head><body>{frame6(b,cp)}</body></html>')
