import math,sys
W,H=1972,1248; OP=0.62; M=28
MAXH=float(sys.argv[1]) if len(sys.argv)>1 else 0.74; CAP=float(sys.argv[2]) if len(sys.argv)>2 else 210
def T(x,y,s,txt,fill='#fff',w=800): return f'<text x="{x:.1f}" y="{y:.1f}" font-family="Figtree" font-weight="{w}" font-size="{s:.1f}" fill="{fill}" text-anchor="middle" dominant-baseline="central">{txt}</text>'
def disc(x,y,r,fill,st='rgba(255,255,255,.38)',sw=3): return f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{r:.1f}" fill="{fill}" stroke="{st}" stroke-width="{sw}"/>'
DARK='rgba(20,22,28,.80)'
def btn(x,y,r,fill,label,lc='#fff'): return disc(x,y,r,fill)+T(x,y,r*0.85,label,lc)
def small(x,y,r,label,fill=DARK): return disc(x,y,r,fill)+T(x,y,r*(0.62 if len(label)<3 else 0.42 if len(label)<6 else 0.34),label)
def dpad(x,y,r,c=DARK):
    a=r*0.34
    p=f'M{x-a} {y-r}h{2*a}v{r-a}h{r-a}v{2*a}h{-(r-a)}v{r-a}h{-2*a}v{-(r-a)}h{-(r-a)}v{-2*a}h{r-a}z'
    tri=lambda dx,dy,rot: f'<path d="M{x+dx} {y+dy-r*0.11}l{-r*0.11} {r*0.16}h{r*0.22}z" fill="rgba(255,255,255,.72)" transform="rotate({rot} {x+dx} {y+dy})"/>'
    return f'<circle cx="{x}" cy="{y}" r="{r}" fill="rgba(20,22,28,.35)"/><path d="{p}" fill="{c}" stroke="rgba(255,255,255,.38)" stroke-width="3" stroke-linejoin="round"/>'+tri(0,-r*0.7,0)+tri(0,r*0.7,180)+tri(-r*0.7,0,-90)+tri(r*0.7,0,90)
def stick(x,y,r): return disc(x,y,r,'rgba(20,22,28,.5)')+disc(x,y,r*0.5,'rgba(70,74,86,.92)','rgba(255,255,255,.55)')
def ps(x,y,r,k):
    c={'x':'#9DB7FF','o':'#FF8A8A','s':'#F49BE3','t':'#7FE8C8'}[k]; q=r*0.42
    g={'x':f'<path d="M{x-q} {y-q}L{x+q} {y+q}M{x+q} {y-q}L{x-q} {y+q}" stroke="{c}" stroke-width="{r*0.13}" stroke-linecap="round"/>',
       'o':f'<circle cx="{x}" cy="{y}" r="{q*1.05}" fill="none" stroke="{c}" stroke-width="{r*0.13}"/>',
       's':f'<rect x="{x-q}" y="{y-q}" width="{2*q}" height="{2*q}" fill="none" stroke="{c}" stroke-width="{r*0.13}"/>',
       't':f'<path d="M{x} {y-q*1.15}L{x+q*1.05} {y+q*.75}H{x-q*1.05}Z" fill="none" stroke="{c}" stroke-width="{r*0.13}" stroke-linejoin="round"/>'}[k]
    return disc(x,y,r,DARK)+g
def face(x,y,r,labels,colors,lcs=None,rot=0,n=4,shapes=False):
    out=f'<circle cx="{x}" cy="{y}" r="{r}" fill="rgba(20,22,28,.25)"/>'
    if n==4: br=r*0.33; d=r*0.62; pos=[(0,d),(d,0),(-d,0),(0,-d)]
    else: br=r*0.40; d=r*0.48; pos=[(d,0),(-d,0)]
    for i,(dx,dy) in enumerate(pos):
        a=math.radians(rot); px=x+dx*math.cos(a)-dy*math.sin(a); py=y+dx*math.sin(a)+dy*math.cos(a)
        out+=ps(px,py,br,labels[i]) if shapes else btn(px,py,br,colors[i],labels[i],(lcs or ['#fff']*4)[i])
    return out
def six(x,y,r):
    out=f'<circle cx="{x}" cy="{y}" r="{r}" fill="rgba(20,22,28,.25)"/>'; br=r*0.27
    for i,(lab,row) in enumerate([('A',1),('B',1),('C',1),('X',0),('Y',0),('Z',0)]):
        k=i%3; px=x+(k-1)*r*0.6; py=y+(0.30 if row else -0.36)*r-(k-1)*r*0.14
        out+=btn(px,py,br*(1 if row else .85),DARK if row else 'rgba(60,62,72,.85)',lab,'#ddd' if row else '#ccc')
    return out
def cpad(x,y,r):
    out=''
    for ang,(dx,dy) in zip([0,180,-90,90],[(0,-1),(0,1),(-1,0),(1,0)]):
        px,py=x+dx*r*0.55,y+dy*r*0.55
        out+=disc(px,py,r*0.36,'rgba(205,160,30,.88)')+f'<path d="M{px} {py-r*0.13}l{-r*0.12} {r*0.17}h{r*0.24}z" fill="#2a2a2a" transform="rotate({ang} {px} {py})"/>'
    return out+T(x,y,r*0.26,'C','#f2c94c')
RS=0.40
def half(primary, items, side, maxW=W*0.40):
    pts=[(0,0,1)]
    for ang,sc,_ in items:
        r=RS*sc; d=1+r+0.10
        pts.append((math.cos(math.radians(ang))*d, -math.sin(math.radians(ang))*d, r))
    x0=min(p[0]-p[2] for p in pts); x1=max(p[0]+p[2] for p in pts); y0=min(p[1]-p[2] for p in pts); y1=max(p[1]+p[2] for p in pts)
    s=min(CAP, maxW/(x1-x0), H*MAXH/(y1-y0))
    ox=M-x0*s if side=='L' else W-M-x1*s
    oy=H-M-y1*s
    out=primary(ox,oy,s)
    for (ang,sc,dr),(px,py,r) in zip(items,pts[1:]): out+=dr(ox+px*s,oy+py*s,r*s)
    return out
L=lambda t: (lambda x,y,r: small(x,y,r,t))
STK=lambda x,y,r: stick(x,y,r); DP=lambda x,y,r: dpad(x,y,r); MENU=lambda x,y,r: small(x,y,r,'⋯')
styles={
 'Xbox':(half(lambda x,y,s: stick(x,y,s),[(90,1,L('LB')),(130,1,L('LT')),(50,1,L('VIEW')),(-80,1.9,DP)],'L')
        +half(lambda x,y,s: face(x,y,s,['A','B','X','Y'],['#2E9E3E','#D03A3A','#2F6FD0','#D9A82A']),[(90,1,L('RB')),(50,1,L('RT')),(130,1,L('MENU')),(-100,1.9,STK),(-55,1,MENU)],'R'),
        'Left stick on top with the d-pad below it, as on the controller; right stick below the face buttons'),
 'DualSense':(half(DP,[(90,1,L('L1')),(130,1,L('L2')),(50,1,L('CREATE')),(-80,1.9,STK)],'L')
        +half(lambda x,y,s: face(x,y,s,'xost',None,shapes=True),[(90,1,L('R1')),(50,1,L('R2')),(130,1,L('OPTIONS')),(-100,1.9,STK),(-55,1,MENU)],'R')
        +f'<rect x="{W/2-130}" y="40" width="260" height="70" rx="22" fill="rgba(20,22,28,.6)" stroke="rgba(255,255,255,.35)" stroke-width="3"/>'+T(W/2,75,24,'Touchpad','rgba(255,255,255,.8)',700),
        'D-pad on top, both sticks below toward the middle; touchpad button at the top centre'),
 'Switch':(half(lambda x,y,s: stick(x,y,s),[(90,1,L('L')),(130,1,L('ZL')),(50,1,L('−')),(-80,1.9,DP)],'L')
        +half(lambda x,y,s: face(x,y,s,['B','A','Y','X'],[DARK]*4),[(90,1,L('R')),(50,1,L('ZR')),(130,1,L('+')),(-100,1.9,STK),(-55,1,MENU)],'R'),
        "Like Xbox, with Nintendo's A on the right and B at the bottom"),
 'SNES':(half(DP,[(120,1,L('L')),(60,1,L('SELECT'))],'L')
        +half(lambda x,y,s: face(x,y,s,['B','A','Y','X'],['#E9C93B','#D9473B','#3BAA5A','#3F6FD0']),[(60,1,L('R')),(120,1,L('START')),(-60,1,MENU)],'R'),
        "L above the d-pad, Select and Start on the inner side, the four colours"),
 'NES':(half(DP,[(120,1,L('SELECT'))],'L')
        +half(lambda x,y,s: face(x,y,s,['A','B'],['#C8323A','#C8323A'],n=2,rot=-20),[(60,1,L('START')),(-60,1,MENU)],'R'),
        'D-pad and two red buttons, Select and Start above them'),
 'Genesis':(half(DP,[(120,1,L('MODE')),(90,1,L('START'))],'L')
        +half(lambda x,y,s: six(x,y,s),[(-60,1,MENU)],'R'),
        'Six buttons: A B C below X Y Z, slanted like the original; Start and Mode by the d-pad'),
 'N64':(half(DP,[(120,1,L('L')),(60,1,L('Z')),(-80,1.9,STK)],'L')
        +half(lambda x,y,s: face(x,y,s,['A','B'],['#2F5FC8','#2E9E3E'],n=2,rot=35),[(60,1,L('R')),(120,1,(lambda x,y,r: disc(x,y,r,'rgba(180,40,40,.85)')+T(x,y,r*0.42,'START'))),(-100,1.9,cpad),(-55,1,MENU)],'R'),
        'Stick below the d-pad, Z by L, the yellow C buttons below A and B'),
 'Game Boy':(half(DP,[(120,1,L('SELECT'))],'L')
        +half(lambda x,y,s: face(x,y,s,['A','B'],['#A8235F','#A8235F'],n=2,rot=-30),[(60,1,L('START')),(-60,1,MENU)],'R'),
        'D-pad and the slanted magenta B A, Select and Start above'),
}
game=open('pad_Xbox.html').read()
gs=game[game.index('<defs><linearGradient id="sky"'):game.index('<g opacity')]
css='@font-face{font-family:Figtree;src:url(Figtree.ttf);font-weight:100 900}@font-face{font-family:Unbounded;src:url(Unbounded.ttf);font-weight:100 900}html,body{margin:0;overflow:hidden}'
def frame(body,cap=None):
    c=f'<div style="position:absolute;left:50%;transform:translateX(-50%);top:22px;font:700 26px Figtree;color:#fff;background:rgba(0,0,0,.55);padding:8px 18px;border-radius:12px;white-space:nowrap">{cap}</div>' if cap else ''
    return f'<div style="position:relative;width:{W}px;height:{H}px"><svg width="{W}" height="{H}" viewBox="0 0 {W} {H}">{gs}<g opacity="{OP}">{body}</g></svg>{c}</div>'
names=list(styles)
for n,(b,cap) in styles.items():
    open(f'v2_{n.replace(" ","_")}.html','w').write(f'<html><head><style>{css}</style></head><body>{frame(b,n+": "+cap)}</body></html>')
cells=''.join(f'<div style="width:{W*.45}px;height:{H*.45+64}px;overflow:hidden"><div style="transform:scale(.45);transform-origin:0 0;width:{W}px">{frame(styles[n][0])}<div style="font:800 64px Unbounded;color:#fff;margin-top:16px">{n}</div></div></div>' for n in names)
open('v2_overview.html','w').write(f'<html><head><style>{css}body{{background:#16171B;padding:40px}}</style></head><body><div style="font:800 50px Unbounded;color:#fff;margin-bottom:8px">On-screen pad styles, v2</div><div style="font:600 26px Figtree;color:#B5BAC3;margin-bottom:28px">Laid out like Lemuroid (open source, RadialGamePad): two halves in the bottom corners, each a main control with buttons on a ring around it</div><div style="display:grid;grid-template-columns:repeat(4,{W*.45}px);gap:30px 26px">{cells}</div></body></html>')
