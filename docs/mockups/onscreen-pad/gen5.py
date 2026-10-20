import math
exec(open('gen4.py').read().split("styles={")[0])   # primitives, scene(), constants
def T2(x,y,s,t,fill,w=800,ls=0): return f'<text x="{x:.1f}" y="{y:.1f}" font-family="Figtree" font-weight="{w}" font-size="{s:.1f}" fill="{fill}" text-anchor="middle" dominant-baseline="central" letter-spacing="{ls}">{t}</text>'
DEF5='''<filter id="grain"><feTurbulence type="fractalNoise" baseFrequency=".9" numOctaves="2" seed="3"/><feColorMatrix values="0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 .05 0"/><feComposite in2="SourceGraphic" operator="in"/></filter>
<linearGradient id="body" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#E4E4E8"/><stop offset="1" stop-color="#C9C9CF"/></linearGradient>
<linearGradient id="tab" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#5A5B61"/><stop offset="1" stop-color="#3A3B40"/></linearGradient>
<radialGradient id="dish" cx=".5" cy=".45" r=".55"><stop offset="0" stop-color="#9A9AA3"/><stop offset=".85" stop-color="#8A8A93"/><stop offset="1" stop-color="#6E6E77"/></radialGradient>
<linearGradient id="dpadg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#4A4B50"/><stop offset=".5" stop-color="#2C2D31"/><stop offset="1" stop-color="#1A1B1E"/></linearGradient>
<filter id="dsh" x="-20%" y="-20%" width="140%" height="140%"><feDropShadow dx="0" dy="6" stdDeviation="6" flood-color="#000" flood-opacity=".35"/></filter>
<filter id="inset"><feOffset dy="4"/><feGaussianBlur stdDeviation="4" result="o"/><feComposite in="SourceGraphic" in2="o" operator="out" result="i"/><feFlood flood-color="#000" flood-opacity=".45"/><feComposite in2="i" operator="in"/><feComposite in2="SourceGraphic" operator="over"/></filter>'''
def snes_dpad(x,y,r):
    a=r*.34; k=a*.35
    p=f'M{x-a} {y-r+k} Q{x-a} {y-r} {x-a+k} {y-r} H{x+a-k} Q{x+a} {y-r} {x+a} {y-r+k} V{y-a} H{x+r-k} Q{x+r} {y-a} {x+r} {y-a+k} V{y+a-k} Q{x+r} {y+a} {x+r-k} {y+a} H{x+a} V{y+r-k} Q{x+a} {y+r} {x+a-k} {y+r} H{x-a+k} Q{x-a} {y+r} {x-a} {y+r-k} V{y+a} H{x-r+k} Q{x-r} {y+a} {x-r} {y+a-k} V{y-a+k} Q{x-r} {y-a} {x-r+k} {y-a} H{x-a} Z'
    tri=lambda rot: f'<path d="M{x} {y-r*.86} l{-r*.14} {r*.2} h{r*.28} z" fill="#151518" opacity=".9" transform="rotate({rot} {x} {y})"/><path d="M{x} {y-r*.84} l{-r*.11} {r*.16}" stroke="#6a6b70" stroke-width="2" transform="rotate({rot} {x} {y})"/>'
    return (f'<path d="{p}" fill="#AFAFB6" transform="translate(0 5)" opacity=".6"/>'
            f'<path d="{p}" fill="url(#dpadg)" stroke="#111" stroke-width="2.5" filter="url(#dsh)"/>'
            f'<path d="{p}" fill="none" stroke="#fff" stroke-opacity=".12" stroke-width="3" transform="translate(-1.5 -1.5)"/>'
            +''.join(tri(k) for k in (0,90,180,270))+f'<circle cx="{x}" cy="{y}" r="{a*.62}" fill="#1c1d20" filter="url(#inset)"/>')
def snes_btn(x,y,r,c,l,lc):
    g=f'sb{l}'
    return (f'<defs><radialGradient id="{g}" cx=".40" cy=".32" r=".8"><stop offset="0" stop-color="{shade(c,1.45)}"/><stop offset=".6" stop-color="{c}"/><stop offset="1" stop-color="{shade(c,.6)}"/></radialGradient></defs>'
            f'<circle cx="{x}" cy="{y}" r="{r*1.08}" fill="#6a6a73"/>'
            f'<circle cx="{x}" cy="{y}" r="{r}" fill="url(#{g})" stroke="{shade(c,.5)}" stroke-width="2.5" filter="url(#dsh)"/>'
            f'<ellipse cx="{x-r*.15}" cy="{y-r*.38}" rx="{r*.55}" ry="{r*.3}" fill="#fff" opacity=".35"/>'
            +T2(x,y+r*.04,r*.78,l,lc,800))
def snes_cluster(x,y,R):
    d=R*.52; r=R*.30
    out=f'<circle cx="{x}" cy="{y}" r="{R}" fill="url(#dish)" filter="url(#inset)"/>'
    for (dx,dy,c,l,lc) in [(0,-d,'#2C55C8','X','#173080'),(d,0,'#D7362C','A','#80150f'),(0,d,'#E9C22F','B','#7a5c00'),(-d,0,'#2E9C4F','Y','#0f5427')]:
        out+=snes_btn(x+dx,y+dy,r,c,l,lc)
    return out
def small_round(x,y,r,label):
    return (f'<circle cx="{x}" cy="{y}" r="{r*1.15}" fill="#B7B7BE" filter="url(#inset)"/><circle cx="{x}" cy="{y}" r="{r}" fill="#9E9EA6" stroke="#7d7d85" stroke-width="2" filter="url(#dsh)"/>'
            +T2(x,y+r*2.0,r*.62,label,'#4a4a52',800,1))
def tab(x,y,w,h,label,side):
    rr=h*.55
    p=(f'M{x} {y+h} V{y+rr} Q{x} {y} {x+rr} {y} H{x+w-rr*.4} Q{x+w} {y} {x+w} {y+h*.5} V{y+h} Z' if side=='L' else
       f'M{x+w} {y+h} V{y+rr} Q{x+w} {y} {x+w-rr} {y} H{x+rr*.4} Q{x} {y} {x} {y+h*.5} V{y+h} Z')
    return f'<path d="{p}" fill="url(#tab)" stroke="#2a2b2f" stroke-width="2"/>'+T2(x+w/2,y+h*.5,h*.42,label,'#26272b',800)+T2(x+w/2-1,y+h*.5-1.5,h*.42,label,'#6e6f75',800)
def wordmark(x,y,s,c='#2b2b30'):
    return (f'<g transform="translate({x-s*3.7} {y}) scale({s/40})"><circle cx="20" cy="0" r="16" fill="#1A1630"/>'
            f'<clipPath id="wm"><circle cx="20" cy="0" r="10"/></clipPath><g clip-path="url(#wm)"><rect x="8" y="-12" width="24" height="24" fill="#FF8A3C"/><rect x="8" y="-12" width="24" height="9" fill="#FFD25A"/><rect x="8" y="2" width="24" height="2" fill="#1A1630"/><rect x="8" y="6" width="24" height="2.5" fill="#1A1630"/><circle cx="25" cy="-4" r="7" fill="#1A1630"/></g></g>'
            +T2(x+s*.55,y,s*.85,'MOONCAST',c,800,s*.12))
gameS=scene()
def game_svg(x,y,w,h):
    # the drawn game scene, scaled into a 4:3 picture
    return f'<svg x="{x}" y="{y}" width="{w}" height="{h}" viewBox="{(W-H*4/3)/2} 0 {H*4/3} {H}" preserveAspectRatio="xMidYMid slice">{gameS}</svg>'
css='@font-face{font-family:Figtree;src:url(Figtree.ttf);font-weight:100 900}@font-face{font-family:Unbounded;src:url(Unbounded.ttf);font-weight:100 900}html,body{margin:0;overflow:hidden;background:#000}'
# ---- upright: the game on top, the controller below (the Fold cover, 1248 x 1972)
PW,PH=1248,1972
gh=int(PW*3/4); gy=110
body_y=gy+gh+10
o=f'<svg width="{PW}" height="{PH}" viewBox="0 0 {PW} {PH}"><defs>{DEFS}{DEF5}</defs><rect width="{PW}" height="{PH}" fill="#000"/>'
o+=f'<g font-family="Figtree" font-weight="600" font-size="38" fill="#ddd"><text x="40" y="62">8:24</text><text x="{PW-130}" y="62">47%</text></g>'
o+=game_svg(0,gy,PW,gh)
o+=f'<path d="M0 {body_y+70} Q0 {body_y} 70 {body_y} H{PW-70} Q{PW} {body_y} {PW} {body_y+70} V{PH} H0 Z" fill="url(#body)"/><path d="M0 {body_y+70} Q0 {body_y} 70 {body_y} H{PW-70} Q{PW} {body_y} {PW} {body_y+70} V{PH} H0 Z" fill="#000" filter="url(#grain)" opacity=".9"/>'
o+=tab(0,body_y-2,300,70,'L','L')+tab(PW-300,body_y-2,300,70,'R','R')
o+=wordmark(PW/2,body_y+44,34)
cy=body_y+400
o+=snes_dpad(300,cy,215)+snes_cluster(905,cy,300)
o+=small_round(110,PH-175,30,'MENU')+small_round(PW/2-90,PH-175,30,'SELECT')+small_round(PW/2+90,PH-175,30,'START')
o+=f'<rect x="{PW/2-150}" y="{PH-46}" width="300" height="10" rx="5" fill="#8a8a92"/></svg>'
open('v5_SNES_upright.html','w').write(f'<html><head><style>{css}</style></head><body>{o}</body></html>')
# ---- sideways: the game a little smaller, centred; the controller round it (the Fold cover, 1972 x 1248)
SW=400; gw=W-2*SW; gh=int(gw*3/4); gx=SW; gy=(H-gh)//2+20
o=f'<svg width="{W}" height="{H}" viewBox="0 0 {W} {H}"><defs>{DEFS}{DEF5}</defs><rect width="{W}" height="{H}" fill="url(#body)"/><rect width="{W}" height="{H}" fill="#000" filter="url(#grain)" opacity=".9"/>'
o+=f'<rect x="{gx-6}" y="{gy-6}" width="{gw+12}" height="{gh+12}" rx="14" fill="#2a2b30"/>'+game_svg(gx,gy,gw,gh)
o+=tab(0,0,330,72,'L','L')+tab(W-330,0,330,72,'R','R')
o+=snes_dpad(SW/2+10,H*.55,160)+snes_cluster(W-SW/2-10,H*.55,172)
o+=wordmark(W/2,gy/2+10,30)
o+=small_round(W/2-90,gy+gh+(H-gy-gh)/2-18,22,'SELECT')+small_round(W/2+90,gy+gh+(H-gy-gh)/2-18,22,'START')+small_round(SW/2,H*.88,24,'MENU')+'</svg>'
open('v5_SNES_sideways.html','w').write(f'<html><head><style>{css}</style></head><body>{o}</body></html>')
