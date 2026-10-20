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
