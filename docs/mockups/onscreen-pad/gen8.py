exec(open('gen7.py').read().split('def bodyfill')[0])          # the skin's realistic keys, plates and sticks
def shoulder7(x,y,w,h,label,round_side=None):
    rl=h*.5 if round_side=='L' else h*.2; rr=h*.5 if round_side=='R' else h*.2
    p=f'M{x+rl} {y} H{x+w-rr} Q{x+w} {y} {x+w} {y+rr} V{y+h-h*.18} Q{x+w} {y+h} {x+w-h*.18} {y+h} H{x+h*.18} Q{x} {y+h} {x} {y+h-h*.18} V{y+rl} Q{x} {y} {x+rl} {y} Z'
    return f'<path d="{p}" fill="url(#key)" stroke="#1d1e21" stroke-width="2.5" filter="url(#ksh)"/><path d="{p}" fill="#000" filter="url(#tex)" opacity=".38"/>'+T7(x+w/2,y+h/2+1.5,h*.42,label,'#151517')+T7(x+w/2,y+h/2,h*.42,label,'#d2d4d9')
# see-through: the keys stay solid enough to read, their plates and wells are faint, the game shows through
OPK=0.86
def plate(x,y,r):
    a=r*.48
    return f'<path d="M{x-a} {y-r} H{x+a} V{y-a} H{x+r} V{y+a} H{x+a} V{y+r} H{x-a} V{y+a} H{x-r} V{y-a} H{x-a} Z" fill="#B0B2B7" fill-opacity=".38" stroke="#fff" stroke-opacity=".45" stroke-width="2" stroke-linejoin="round"/>'
def stick8(x,y,r):
    return (f'<circle cx="{x}" cy="{y}" r="{r*1.25}" fill="#8F9196" fill-opacity=".35" stroke="#fff" stroke-opacity=".45" stroke-width="2"/>'
            f'<circle cx="{x}" cy="{y}" r="{r}" fill="url(#cap7)" stroke="#121315" stroke-width="5" filter="url(#ksh)"/><circle cx="{x}" cy="{y}" r="{r}" fill="#000" filter="url(#tex)" opacity=".38"/>'
            f'<circle cx="{x}" cy="{y}" r="{r*.80}" fill="none" stroke="#000" stroke-opacity=".25" stroke-width="{r*.05}"/>')
def trigger8(x,y,w,h,label,side):
    k=h*.55
    p=(f'M{x} {y+h} V{y+k} Q{x} {y} {x+k} {y} H{x+w-k*.3} Q{x+w} {y} {x+w} {y+h*.5} V{y+h} Z' if side=='L' else
       f'M{x+w} {y+h} V{y+k} Q{x+w} {y} {x+w-k} {y} H{x+k*.3} Q{x} {y} {x} {y+h*.5} V{y+h} Z')
    return f'<path d="{p}" fill="url(#key)" stroke="#1d1e21" stroke-width="2.5" filter="url(#ksh)"/><path d="{p}" fill="#000" filter="url(#tex)" opacity=".38"/>'+T7(x+w/2,y+h*.55,h*.38,label,'#2a2b2e')+T7(x+w/2,y+h*.55-1.5,h*.38,label,'#d2d4d9')
def xcap(x,y,r,letter,col):   # Xbox: a dark cap with its letter in colour
    return (f'<circle cx="{x}" cy="{y}" r="{r}" fill="url(#cap7)" stroke="#141518" stroke-width="3" filter="url(#ksh)"/><circle cx="{x}" cy="{y}" r="{r}" fill="#000" filter="url(#tex)" opacity=".38"/>'
            f'<circle cx="{x}" cy="{y}" r="{r*.93}" fill="none" stroke="#fff" stroke-opacity=".08" stroke-width="3"/>'+T2(x,y+r*.04,r*.95,letter,col,800))
def xface(x,y,r):
    d=r*.98; br=r*.46
    return plate(x,y,r*1.5)+xcap(x,y-d,br,'Y','#F2C230')+xcap(x+d,y,br,'B','#F0574F')+xcap(x-d,y,br,'X','#4D96FF')+xcap(x,y+d,br,'A','#5FCB5A')
def xdpad(x,y,r):   # Xbox: one cross key on a round base
    a=r*.34; k=a*.4
    p=f'M{x-a} {y-r+k} Q{x-a} {y-r} {x-a+k} {y-r} H{x+a-k} Q{x+a} {y-r} {x+a} {y-r+k} V{y-a} H{x+r-k} Q{x+r} {y-a} {x+r} {y-a+k} V{y+a-k} Q{x+r} {y+a} {x+r-k} {y+a} H{x+a} V{y+r-k} Q{x+a} {y+r} {x+a-k} {y+r} H{x-a+k} Q{x-a} {y+r} {x-a} {y+r-k} V{y+a} H{x-r+k} Q{x-r} {y+a} {x-r} {y+a-k} V{y-a+k} Q{x-r} {y-a} {x-r+k} {y-a} H{x-a} Z'
    tri=lambda rot: f'<path d="M{x} {y-r*.84} l{-r*.11} {r*.15} h{r*.22} z" fill="#8a8c91" transform="rotate({rot} {x} {y})"/>'
    return (f'<circle cx="{x}" cy="{y}" r="{r*1.12}" fill="#8F9196" fill-opacity=".35" stroke="#fff" stroke-opacity=".45" stroke-width="2"/>'
            f'<path d="{p}" fill="url(#key)" stroke="#1d1e21" stroke-width="2.5" filter="url(#ksh)"/><path d="{p}" fill="#000" filter="url(#tex)" opacity=".38"/>'+''.join(tri(t) for t in (0,90,180,270)))
def small_icon(x,y,r,kind):
    o=f'<circle cx="{x}" cy="{y}" r="{r}" fill="url(#key)" stroke="#1d1e21" stroke-width="2" filter="url(#ksh)"/>'
    if kind=='view': o+=f'<rect x="{x-r*.45}" y="{y-r*.36}" width="{r*.52}" height="{r*.42}" rx="3" fill="none" stroke="#c9cbd0" stroke-width="{r*.1}"/><rect x="{x-r*.15}" y="{y-r*.08}" width="{r*.52}" height="{r*.42}" rx="3" fill="#3b3c40" stroke="#c9cbd0" stroke-width="{r*.1}"/>'
    elif kind=='menu': o+=''.join(f'<rect x="{x-r*.4}" y="{y+dy*r-r*.05}" width="{r*.8}" height="{r*.1}" rx="2" fill="#c9cbd0"/>' for dy in (-.26,0,.26))
    else: o+=T2(x,y,r*.7,'⋯','#c9cbd0',800)
    return o
def P(fx,fy): return (fx*W, fy*H)
gameF=scene()
css='@font-face{font-family:Figtree;src:url(Figtree.ttf);font-weight:100 900}@font-face{font-family:Unbounded;src:url(Unbounded.ttf);font-weight:100 900}html,body{margin:0;overflow:hidden;background:#000}'
def page(name,body,cap):
    o=f'<svg width="{W}" height="{H}" viewBox="0 0 {W} {H}"><defs>{DEFS}{DEF5}{DEF7}</defs>{gameF}<g opacity="{OPK}">{body}</g></svg>'
    c=f'<div style="position:absolute;left:50%;transform:translateX(-50%);top:170px;font:700 25px Figtree;color:#fff;background:rgba(0,0,0,.6);padding:8px 18px;border-radius:12px;white-space:nowrap">{cap}</div>'
    open(name,'w').write(f'<html><head><style>{css}</style></head><body><div style="position:relative;width:{W}px;height:{H}px">{o}{c}</div></body></html>')
# PlayStation: the skin's keys, floating over the full-screen game
ps=(shoulder7(30,26,230,84,'L1','L')+trigger8(30,126,230,96,'L2','L')
   +shoulder7(W-260,26,230,84,'R1','R')+trigger8(W-260,126,230,96,'R2','R')
   +touchpad7(W/2,70,300,84)+create7(W/2-250,62,40)+options7(W/2+250,62,40)
   +dpad7(*P(.235,.47),112)+stick8(*P(.115,.75),112)
   +face7(*P(.86,.47),102)+stick8(*P(.745,.80),104)
   +small_icon(*P(.5,.92),32,'mc'))
page('v8_PlayStation.html',ps,'PlayStation: the DualShocked keys, see-through over the full-screen game')
xb=(shoulder7(30,26,230,84,'LB','L')+trigger8(30,126,230,96,'LT','L')
   +shoulder7(W-260,26,230,84,'RB','R')+trigger8(W-260,126,230,96,'RT','R')
   +small_icon(W/2-90,66,34,'view')+small_icon(W/2,66,34,'mc')+small_icon(W/2+90,66,34,'menu')
   +stick8(*P(.115,.56),112)+xdpad(*P(.245,.80),100)
   +xface(*P(.86,.47),102)+stick8(*P(.745,.80),104))
page('v8_Xbox.html',xb,'Xbox: the same keys with A B X Y in colour, left stick above the d-pad')
