import math
W,H=1972,1248; M=28; OP=0.70
uid=[0]
def nid(p): uid[0]+=1; return f'{p}{uid[0]}'
def T(x,y,s,txt,fill='#fff',w=800,fam='Figtree'): return f'<text x="{x:.1f}" y="{y:.1f}" font-family="{fam}" font-weight="{w}" font-size="{s:.1f}" fill="{fill}" text-anchor="middle" dominant-baseline="central">{txt}</text>'
def shade(c,f):
    c=c.lstrip('#'); r,g,b=[int(c[i:i+2],16) for i in (0,2,4)]
    f2=lambda v: max(0,min(255,int(v*f if f<1 else v+(255-v)*(f-1))))
    return '#%02x%02x%02x'%(f2(r),f2(g),f2(b))
DEFS='''<filter id="sh" x="-30%" y="-30%" width="160%" height="160%"><feDropShadow dx="0" dy="5" stdDeviation="5" flood-color="#000" flood-opacity=".45"/></filter>
<filter id="sh2" x="-30%" y="-30%" width="160%" height="160%"><feDropShadow dx="0" dy="3" stdDeviation="3" flood-color="#000" flood-opacity=".4"/></filter>'''
def cap(x,y,r,color,label=None,lc='#fff',ls=None,gloss=.55):
    # a convex plastic button: base, light from the top left, darker rim, a soft shadow
    g=nid('g'); h=nid('h')
    return (f'<defs><radialGradient id="{g}" cx=".38" cy=".30" r=".85"><stop offset="0" stop-color="{shade(color,1.35)}"/><stop offset=".55" stop-color="{color}"/><stop offset="1" stop-color="{shade(color,.55)}"/></radialGradient>'
            f'<radialGradient id="{h}" cx=".35" cy=".22" r=".5"><stop offset="0" stop-color="#fff" stop-opacity="{gloss}"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient></defs>'
            f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{r:.1f}" fill="url(#{g})" stroke="{shade(color,.45)}" stroke-width="{max(2,r*.05):.1f}" filter="url(#sh)"/>'
            f'<ellipse cx="{x-r*.12:.1f}" cy="{y-r*.32:.1f}" rx="{r*.62:.1f}" ry="{r*.42:.1f}" fill="url(#{h})"/>'
            +(T(x,y+r*.04,ls or r*.82,label,lc) if label else ''))
def well(x,y,r,color='#1b1c22'):
    # the dish a cluster sits in (a faint moulded recess)
    g=nid('w')
    return f'<defs><radialGradient id="{g}" cx=".5" cy=".42" r=".6"><stop offset="0" stop-color="{color}" stop-opacity=".18"/><stop offset=".85" stop-color="{color}" stop-opacity=".42"/><stop offset="1" stop-color="#fff" stop-opacity=".18"/></radialGradient></defs><circle cx="{x:.1f}" cy="{y:.1f}" r="{r:.1f}" fill="url(#{g})"/>'
def pill(x,y,w,h,color,label=None,lc='#fff',rot=0,ls=None):
    g=nid('p')
    return (f'<g transform="rotate({rot} {x:.1f} {y:.1f})"><defs><linearGradient id="{g}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{shade(color,1.4)}"/><stop offset=".5" stop-color="{color}"/><stop offset="1" stop-color="{shade(color,.55)}"/></linearGradient></defs>'
            f'<rect x="{x-w/2:.1f}" y="{y-h/2:.1f}" width="{w:.1f}" height="{h:.1f}" rx="{h/2:.1f}" fill="url(#{g})" stroke="{shade(color,.45)}" stroke-width="2.5" filter="url(#sh2)"/>'
            f'<rect x="{x-w/2+h*.25:.1f}" y="{y-h/2+h*.12:.1f}" width="{w-h*.5:.1f}" height="{h*.28:.1f}" rx="{h*.14:.1f}" fill="#fff" opacity=".22"/>'
            +(T(x,y,ls or h*.42,label,lc) if label else '')+'</g>')
def trigger(x,y,w,h,color,label,side):
    g=nid('t'); k=1 if side=='L' else -1
    p=f'M{x-w/2} {y+h/2} L{x-w/2} {y-h*.1} Q{x-w/2} {y-h/2} {x-w/2+w*.25} {y-h/2} L{x+w/2-w*.25} {y-h/2} Q{x+w/2} {y-h/2} {x+w/2} {y-h*.1} L{x+w/2} {y+h/2} Z'
    return (f'<defs><linearGradient id="{g}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{shade(color,1.45)}"/><stop offset="1" stop-color="{shade(color,.6)}"/></linearGradient></defs>'
            f'<path d="{p}" fill="url(#{g})" stroke="{shade(color,.4)}" stroke-width="2.5" filter="url(#sh2)"/>'+T(x,y+h*.08,h*.40,label,'#e8e8e8'))
def dpad(x,y,r,color='#202126',kind='cross'):
    g=nid('d'); a=r*0.33
    if kind=='segments':   # PlayStation: four separate arrows
        out=''
        for rot in (0,90,180,270):
            p=f'M{x-a*.95} {y-r} H{x+a*.95} V{y-a*1.05} L{x} {y-a*.15} L{x-a*.95} {y-a*1.05} Z'
            out+=f'<g transform="rotate({rot} {x} {y})"><path d="{p}" fill="{color}" stroke="{shade(color,.5)}" stroke-width="2.5" filter="url(#sh2)"/><path d="M{x} {y-r*.86} l{-r*.1} {r*.14} h{r*.2} z" fill="#d8d8d8" opacity=".8"/></g>'
        return well(x,y,r*1.05)+out
    p=f'M{x-a} {y-r+a*.25} Q{x-a} {y-r} {x-a+a*.25} {y-r} H{x+a-a*.25} Q{x+a} {y-r} {x+a} {y-r+a*.25} V{y-a} H{x+r-a*.25} Q{x+r} {y-a} {x+r} {y-a+a*.25} V{y+a-a*.25} Q{x+r} {y+a} {x+r-a*.25} {y+a} H{x+a} V{y+r-a*.25} Q{x+a} {y+r} {x+a-a*.25} {y+r} H{x-a+a*.25} Q{x-a} {y+r} {x-a} {y+r-a*.25} V{y+a} H{x-r+a*.25} Q{x-r} {y+a} {x-r} {y+a-a*.25} V{y-a+a*.25} Q{x-r} {y-a} {x-r+a*.25} {y-a} H{x-a} Z'
    tri=lambda rot: f'<path d="M{x} {y-r*.84} l{-r*.1} {r*.15} h{r*.2} z" fill="{shade(color,1.9)}" opacity=".55" transform="rotate({rot} {x} {y})"/>'
    disc=f'<circle cx="{x}" cy="{y}" r="{r*1.02}" fill="{shade(color,1.15)}" opacity=".35"/>' if kind=='disc' else ''
    return (well(x,y,r*1.08)+disc+f'<defs><linearGradient id="{g}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{shade(color,1.5)}"/><stop offset=".5" stop-color="{color}"/><stop offset="1" stop-color="{shade(color,.6)}"/></linearGradient></defs>'
            f'<path d="{p}" fill="url(#{g})" stroke="{shade(color,.45)}" stroke-width="3" filter="url(#sh)"/>'
            f'<circle cx="{x}" cy="{y}" r="{a*.55}" fill="{shade(color,.7)}" opacity=".7"/>'+''.join(tri(k) for k in (0,90,180,270)))
def stick(x,y,r,color='#2a2b30',ring='#151519'):
    g=nid('s'); c=nid('c')
    return (f'<defs><radialGradient id="{g}" cx=".5" cy=".45" r=".55"><stop offset=".7" stop-color="{ring}" stop-opacity=".55"/><stop offset="1" stop-color="#fff" stop-opacity=".2"/></radialGradient>'
            f'<radialGradient id="{c}" cx=".45" cy=".35" r=".7"><stop offset="0" stop-color="{shade(color,1.25)}"/><stop offset=".7" stop-color="{color}"/><stop offset="1" stop-color="{shade(color,.5)}"/></radialGradient></defs>'
            f'<circle cx="{x}" cy="{y}" r="{r}" fill="url(#{g})"/>'
            f'<circle cx="{x}" cy="{y}" r="{r*.56}" fill="url(#{c})" stroke="{shade(color,.4)}" stroke-width="3" filter="url(#sh)"/>'
            f'<circle cx="{x}" cy="{y+r*.03}" r="{r*.40}" fill="none" stroke="#000" stroke-opacity=".35" stroke-width="{r*.05}"/>'
            f'<circle cx="{x}" cy="{y+r*.03}" r="{r*.27}" fill="{shade(color,.8)}" opacity=".6"/>')
def psym(x,y,r,k,col):
    q=r*.42; sw=r*.12
    return {'x':f'<path d="M{x-q} {y-q}L{x+q} {y+q}M{x+q} {y-q}L{x-q} {y+q}" stroke="{col}" stroke-width="{sw}" stroke-linecap="round"/>',
            'o':f'<circle cx="{x}" cy="{y}" r="{q*1.05}" fill="none" stroke="{col}" stroke-width="{sw}"/>',
            's':f'<rect x="{x-q*.95}" y="{y-q*.95}" width="{q*1.9}" height="{q*1.9}" fill="none" stroke="{col}" stroke-width="{sw}"/>',
            't':f'<path d="M{x} {y-q*1.15}L{x+q*1.05} {y+q*.72}H{x-q*1.05}Z" fill="none" stroke="{col}" stroke-width="{sw}" stroke-linejoin="round"/>'}[k]
def face(x,y,r,spec,rot=0,n=4,well_c='#1b1c22'):
    out=well(x,y,r*1.02,well_c)
    if n==4: br=r*0.34; d=r*0.62; pos=[(0,d),(d,0),(-d,0),(0,-d)]
    else: br=r*0.40; d=r*0.48; pos=[(d,0),(-d,0)]
    for i,(dx,dy) in enumerate(pos):
        a=math.radians(rot); px=x+dx*math.cos(a)-dy*math.sin(a); py=y+dx*math.sin(a)+dy*math.cos(a)
        color,label,lc=spec[i]
        if label in ('x','o','s','t'): out+=cap(px,py,br,color)+psym(px,py,br,label,lc)
        else: out+=cap(px,py,br,color,label,lc)
    return out
RS=0.40
def half(primary,items,side,maxW=W*0.40,maxH=0.74,capr=210):
    pts=[(0,0,1)]
    for ang,sc,_ in items:
        r=RS*sc; d=1+r+0.10; pts.append((math.cos(math.radians(ang))*d,-math.sin(math.radians(ang))*d,r))
    x0=min(p[0]-p[2] for p in pts); x1=max(p[0]+p[2] for p in pts); y0=min(p[1]-p[2] for p in pts); y1=max(p[1]+p[2] for p in pts)
    s=min(capr,maxW/(x1-x0),H*maxH/(y1-y0)); ox=M-x0*s if side=='L' else W-M-x1*s; oy=H-M-y1*s
    out=primary(ox,oy,s)
    for (ang,sc,dr),(px,py,r) in zip(items,pts[1:]): out+=dr(ox+px*s,oy+py*s,r*s)
    return out
# drawer factories
CAP=lambda c,l,lc='#fff',ls=None: (lambda x,y,r: cap(x,y,r*.92,c,l,lc,ls or r*(.62 if len(l)<3 else .40)))
PILL=lambda c,l,lc='#fff',rot=0: (lambda x,y,r: pill(x,y,r*2.1,r*.95,c,l,lc,rot))
SHOULDER=lambda c,l: (lambda x,y,r: pill(x,y,r*2.3,r*1.05,c,l,'#eee'))
TRIG=lambda c,l,side: (lambda x,y,r: trigger(x,y,r*2.0,r*1.35,c,l,side))
STK=lambda c='#2a2b30': (lambda x,y,r: stick(x,y,r,c))
DPD=lambda c='#202126',k='cross': (lambda x,y,r: dpad(x,y,r*.92,c,k))
def ICON(c,kind):
    def d(x,y,r):
        rr=r*.92; o=cap(x,y,rr,c)
        if kind=='view': o+=f'<rect x="{x-rr*.36}" y="{y-rr*.30}" width="{rr*.46}" height="{rr*.38}" rx="3" fill="none" stroke="#ddd" stroke-width="{rr*.08}"/><rect x="{x-rr*.12}" y="{y-rr*.08}" width="{rr*.46}" height="{rr*.38}" rx="3" fill="{c}" stroke="#ddd" stroke-width="{rr*.08}"/>'
        else: o+=''.join(f'<rect x="{x-rr*.32}" y="{y+dy*rr-rr*.04}" width="{rr*.64}" height="{rr*.09}" rx="2" fill="#ddd"/>' for dy in (-.2,0,.2))
        return o
    return d
MENU=lambda x,y,r: cap(x,y,r*.8,'#3a3d45','⋯','#fff',r*.6)
def cbtns(x,y,r):
    out=''
    for ang,(dx,dy) in zip([0,180,-90,90],[(0,-1),(0,1),(-1,0),(1,0)]):
        px,py=x+dx*r*.58,y+dy*r*.58
        out+=cap(px,py,r*.36,'#E8B417')+f'<path d="M{px} {py-r*.13}l{-r*.12} {r*.17}h{r*.24}z" fill="#3a2a00" transform="rotate({ang} {px} {py})"/>'
    return well(x,y,r*1.0)+out
def six(x,y,r):
    out=well(x,y,r*1.05)
    for i,(lab,row) in enumerate([('A',1),('B',1),('C',1),('X',0),('Y',0),('Z',0)]):
        k=i%3; px=x+(k-1)*r*.6; py=y+(.30 if row else -.36)*r-(k-1)*r*.14
        out+=cap(px,py,r*(.27 if row else .22),'#26262b' if row else '#3a3a42',lab,'#cfd2d8',r*(.2 if row else .16))
    return out
X='#26272c'
styles={
 'Xbox':(half(lambda x,y,s: stick(x,y,s),[(90,1,SHOULDER('#2a2b30','LB')),(132,1,TRIG('#2a2b30','LT','L')),(48,1,ICON('#2a2b30','view')),(-80,1.9,DPD('#2a2b30','disc'))],'L')
        +half(lambda x,y,s: face(x,y,s,[('#1f2024','A','#5DD35D'),('#1f2024','B','#FF5A52'),('#1f2024','X','#4C9BFF'),('#1f2024','Y','#FFD23F')]),
              [(90,1,SHOULDER('#2a2b30','RB')),(48,1,TRIG('#2a2b30','RT','R')),(132,1,ICON('#2a2b30','menu')),(-100,1.9,STK()),(-55,1,MENU)],'R'),
        'Black caps with green, red, blue and yellow letters; bumpers, triggers, the hybrid d-pad, View and Menu'),
 'DualSense':(half(DPD('#3b3d44','segments'),[(90,1,SHOULDER('#2d2e34','L1')),(132,1,TRIG('#2d2e34','L2','L')),(48,1,PILL('#3b3d44','CREATE')),(-80,1.9,STK('#26272c'))],'L')
        +half(lambda x,y,s: face(x,y,s,[('#3a3c44','x','#B7C6F0'),('#3a3c44','o','#F0B7BE'),('#3a3c44','s','#E8BDE0'),('#3a3c44','t','#B5E6D6')]),
              [(90,1,SHOULDER('#2d2e34','R1')),(48,1,TRIG('#2d2e34','R2','R')),(132,1,PILL('#3b3d44','OPTIONS')),(-100,1.9,STK('#26272c')),(-55,1,MENU)],'R')
        +f'<g opacity=".9">{pill(W/2,74,300,74,"#e9eaee","","#333")}</g>'+T(W/2,74,22,'TOUCHPAD','#555',800),
        'Smoky caps with the four pale symbols, separate d-pad arrows, the white touchpad'),
 'Switch':(half(lambda x,y,s: stick(x,y,s,'#2b2c31'),[(90,1,SHOULDER('#26272c','L')),(132,1,TRIG('#26272c','ZL','L')),(48,1,CAP('#2b2c31','−','#ddd')),(-80,1.9,DPD('#2b2c31'))],'L')
        +half(lambda x,y,s: face(x,y,s,[('#2b2c31','B','#eee'),('#2b2c31','A','#eee'),('#2b2c31','Y','#eee'),('#2b2c31','X','#eee')]),
              [(90,1,SHOULDER('#26272c','R')),(48,1,TRIG('#26272c','ZR','R')),(132,1,CAP('#2b2c31','+','#ddd')),(-100,1.9,STK('#2b2c31')),(-55,1,MENU)],'R'),
        'Pro Controller: black caps, white letters in Nintendo positions, L R ZL ZR, − and +'),
 'SNES':(half(DPD('#2a2a2e'),[(120,1,SHOULDER('#9b9ca6','L')),(60,1,PILL('#7d7e88','SELECT','#e8e8ee',-25))],'L')
        +half(lambda x,y,s: face(x,y,s,[('#E9C433','B','#4a3a00'),('#D8382E','A','#fff'),('#2E9D52','Y','#fff'),('#2E5FC9','X','#fff')],rot=-20,well_c='#7b7c86'),
              [(60,1,SHOULDER('#9b9ca6','R')),(120,1,PILL('#7d7e88','START','#e8e8ee',-25)),(-60,1,MENU)],'R'),
        'Four-colour convex buttons tilted in their grey dish, black cross, grey L R, slanted Select and Start'),
 'NES':(half(DPD('#1d1d20'),[(120,1,PILL('#1d1d20','SELECT','#c9c9cf'))],'L')
        +half(lambda x,y,s: face(x,y,s,[('#C42E36','A','#fff'),('#C42E36','B','#fff')],n=2,well_c='#2a2a2e'),[(60,1,PILL('#1d1d20','START','#c9c9cf')),(-60,1,MENU)],'R'),
        'Black cross, the two round red A B, black rubber Select and Start'),
 'Genesis':(half(DPD('#1e1e22','disc'),[(120,1,PILL('#2a2a30','MODE','#bbb')),(90,1,PILL('#2a2a30','START','#ddd'))],'L')
        +half(lambda x,y,s: six(x,y,s),[(-60,1,MENU)],'R'),
        'Six-button pad: big black A B C, smaller X Y Z, the round d-pad, Start and Mode'),
 'N64':(half(DPD('#8d8f99'),[(120,1,SHOULDER('#8d8f99','L')),(60,1,TRIG('#5a5c66','Z','L')),(-80,1.9,STK('#9a9ca6'))],'L')
        +half(lambda x,y,s: face(x,y,s,[('#2D57C2','A','#fff'),('#2B9A47','B','#fff')],n=2,rot=35,well_c='#5a5c66'),
              [(60,1,SHOULDER('#8d8f99','R')),(120,1,CAP('#C8302E','START','#fff')),(-100,1.9,cbtns),(-55,1,MENU)],'R'),
        'Grey stick and cross, blue A, green B, the yellow C buttons, red Start, Z trigger'),
 'Game Boy':(half(DPD('#1e1e22'),[(120,1,PILL('#8a8a92','SELECT','#333',-25))],'L')
        +half(lambda x,y,s: face(x,y,s,[('#A31F55','A','#f3d6e2'),('#A31F55','B','#f3d6e2')],n=2,rot=-28,well_c='#8a8a92'),[(60,1,PILL('#8a8a92','START','#333',-25)),(-60,1,MENU)],'R'),
        'Black cross, the slanted magenta B A, grey rubber Select and Start'),
}
game=open('pad_Xbox.html').read(); gs=game[game.index('<defs><linearGradient id="sky"'):game.index('<g opacity')]
css='@font-face{font-family:Figtree;src:url(Figtree.ttf);font-weight:100 900}@font-face{font-family:Unbounded;src:url(Unbounded.ttf);font-weight:100 900}html,body{margin:0;overflow:hidden}'
def frame(body,cap_=None):
    c=f'<div style="position:absolute;left:50%;transform:translateX(-50%);top:150px;font:700 26px Figtree;color:#fff;background:rgba(0,0,0,.55);padding:8px 18px;border-radius:12px;white-space:nowrap">{cap_}</div>' if cap_ else ''
    return f'<div style="position:relative;width:{W}px;height:{H}px"><svg width="{W}" height="{H}" viewBox="0 0 {W} {H}"><defs>{DEFS}</defs>{gs}<g opacity="{OP}">{body}</g></svg>{c}</div>'
names=list(styles)
for n,(b,cp) in styles.items():
    open(f'v3_{n.replace(" ","_")}.html','w').write(f'<html><head><style>{css}</style></head><body>{frame(b,n+": "+cp)}</body></html>')
cells=''.join(f'<div style="width:{W*.45}px;height:{H*.45+64}px;overflow:hidden"><div style="transform:scale(.45);transform-origin:0 0;width:{W}px">{frame(styles[n][0])}<div style="font:800 64px Unbounded;color:#fff;margin-top:16px">{n}</div></div></div>' for n in names)
open('v3_overview.html','w').write(f'<html><head><style>{css}body{{background:#16171B;padding:40px}}</style></head><body><div style="font:800 50px Unbounded;color:#fff;margin-bottom:8px">On-screen pad styles, v3</div><div style="font:600 26px Figtree;color:#B5BAC3;margin-bottom:28px">Real-looking buttons for each system (colours, shapes, plastic highlights), see-through at 70%, laid out like Lemuroid</div><div style="display:grid;grid-template-columns:repeat(4,{W*.45}px);gap:30px 26px">{cells}</div></body></html>')
