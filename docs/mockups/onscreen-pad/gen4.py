import math
exec(open('prim3.py').read())
OP=0.78
# Microsoft's wheel positions (Xbox touch "standard" layout), in screen-height units from the edges
LX=0.454*H; RX=W-0.356*H; WY=0.52*H
def P(cx,dx,dy): return (cx+dx*H, WY+dy*H)
hd=lambda: None
def scene():
    # a game frame: sky, sun glow, clouds, hills, a tiled platform level, coins, a hero and a HUD
    o='''<defs><linearGradient id="sky4" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#3A7BD5"/><stop offset=".55" stop-color="#7FB8F0"/><stop offset="1" stop-color="#CDE8FF"/></linearGradient>
<radialGradient id="sun4" cx=".5" cy=".5" r=".5"><stop offset="0" stop-color="#FFF6C8"/><stop offset=".35" stop-color="#FFE38A" stop-opacity=".95"/><stop offset="1" stop-color="#FFE38A" stop-opacity="0"/></radialGradient>
<linearGradient id="h1" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#7DBA6E"/><stop offset="1" stop-color="#4E8E4A"/></linearGradient>
<linearGradient id="h2" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#5DA357"/><stop offset="1" stop-color="#2F6E3A"/></linearGradient>
<pattern id="brick" width="64" height="64" patternUnits="userSpaceOnUse"><rect width="64" height="64" fill="#B8642F"/><path d="M0 31h64M0 63h64M31 0v31M0 32v31M63 32v31" stroke="#7A3A16" stroke-width="3"/><path d="M2 2h27M34 34h27" stroke="#E08A4E" stroke-width="3"/></pattern>
<pattern id="grass" width="64" height="40" patternUnits="userSpaceOnUse"><rect width="64" height="40" fill="#4CAF50"/><path d="M0 8 Q8 0 16 8 T32 8 T48 8 T64 8 V40 H0Z" fill="#66CC66"/></pattern></defs>'''
    o+=f'<rect width="{W}" height="{H}" fill="url(#sky4)"/><circle cx="{W*.72}" cy="{H*.18}" r="{H*.22}" fill="url(#sun4)"/>'
    for cx,cy,s in [(300,190,1),(980,140,1.3),(1500,260,.9),(1800,120,.8)]:
        o+=f'<g fill="#fff" opacity=".92"><ellipse cx="{cx}" cy="{cy}" rx="{90*s}" ry="{38*s}"/><ellipse cx="{cx-60*s}" cy="{cy+10*s}" rx="{60*s}" ry="{30*s}"/><ellipse cx="{cx+70*s}" cy="{cy+12*s}" rx="{62*s}" ry="{28*s}"/></g>'
    o+=f'<path d="M0 780 Q300 560 620 760 T1260 720 T1972 700 V1248 H0Z" fill="url(#h1)"/><path d="M0 900 Q380 720 760 880 T1520 860 T1972 840 V1248 H0Z" fill="url(#h2)"/>'
    o+=f'<rect y="1040" width="{W}" height="40" fill="url(#grass)"/><rect y="1080" width="{W}" height="168" fill="url(#brick)"/>'
    for x,y,n in [(660,700,4),(1180,560,3)]:
        o+=f'<rect x="{x}" y="{y}" width="{64*n}" height="64" fill="url(#brick)" stroke="#5a2a10" stroke-width="3"/>'
    o+=f'<rect x="{1180+64}" y="560" width="64" height="64" fill="#F5B83D" stroke="#8a5a00" stroke-width="4"/>'+T(1180+96,592,46,'?','#8a4a00',900)
    for x,y in [(700,630),(780,630),(860,630),(1230,490),(1310,490)]:
        o+=f'<ellipse cx="{x}" cy="{y}" rx="18" ry="26" fill="#FFD23F" stroke="#B8860B" stroke-width="4"/><ellipse cx="{x-4}" cy="{y-6}" rx="5" ry="10" fill="#fff6c8"/>'
    hx,hy=930,960   # hero
    o+=f'<g><rect x="{hx}" y="{hy}" width="64" height="80" rx="10" fill="#E2504E"/><rect x="{hx+8}" y="{hy-50}" width="48" height="52" rx="12" fill="#F2C09A"/><rect x="{hx+4}" y="{hy-60}" width="58" height="22" rx="8" fill="#C0302E"/><circle cx="{hx+42}" cy="{hy-28}" r="5" fill="#222"/><rect x="{hx+6}" y="{hy+60}" width="22" height="20" fill="#3A4FB0"/><rect x="{hx+36}" y="{hy+60}" width="22" height="20" fill="#3A4FB0"/></g>'
    o+=f'<g font-family="Unbounded" font-weight="800" fill="#fff" stroke="#1b2a48" stroke-width="2" paint-order="stroke"><text x="200" y="80" font-size="40">SCORE 012300</text><text x="{W/2-120}" y="80" font-size="40">◉ ×24</text></g>'
    o+=''.join(f'<path d="M{70+i*56} 120 c-12 -16 -40 -10 -40 10 c0 18 24 30 40 42 c16 -12 40 -24 40 -42 c0 -20 -28 -26 -40 -10z" fill="#FF4D6D" stroke="#7a0f25" stroke-width="3" transform="scale(.8) translate({190+i*14} 30)"/>' for i in range(3))
    return o
def two(x,y,r,spec,rot):   # two face buttons on a slant
    return face(x,y,r,spec,rot=rot,n=2)
def sel_start(a='SELECT',b='START',c='#5a5c66',lc='#eee',rot=0):
    return pill(W/2-0.11*H,0.90*H,0.17*H,0.055*H,c,a,lc,rot)+pill(W/2+0.11*H,0.90*H,0.17*H,0.055*H,c,b,lc,rot)
def qmenu(): return cap(0.085*H,0.085*H,0.042*H,'#3a3d45','⋯','#fff',0.04*H)
def lay(left_primary,right_primary,extra):
    return left_primary+right_primary+extra+qmenu()
s=H
def SH(pos,c,l): x,y=pos; return pill(x,y,0.15*s,0.062*s,c,l,'#eee')
def TR(pos,c,l,side): x,y=pos; return trigger(x,y,0.12*s,0.085*s,c,l,side)
def ST(pos,c='#2a2b30',r=0.125): x,y=pos; return stick(x,y,r*s,c)
def DP(pos,c='#202126',k='cross',r=0.12): x,y=pos; return dpad(x,y,r*s,c,k)
def CL(pos,c,l,lc='#eee',r=0.036): x,y=pos; return cap(x,y,r*s,c,l,lc,r*s*.62)
def FACE(spec,rot=0,r=0.135): return face(RX,WY,r*s,spec,rot=rot)
def FACE2(spec,rot,r=0.12): return face(RX,WY,r*s,spec,rot=rot,n=2)
L_LB=P(LX,-.094,-.234); L_LT=P(LX,-.232,-.099); L_DP=P(LX,.232,.232); L_L3=P(LX,-.094,.245)
R_RB=P(RX,.098,-.234); R_RT=P(RX,.234,-.099); R_RS=P(RX,-.232,.232); R_R3=P(RX,.098,.245)
xs=[('#1f2024','A','#5DD35D'),('#1f2024','B','#FF5A52'),('#1f2024','X','#4C9BFF'),('#1f2024','Y','#FFD23F')]
def VIEWMENU():
    x1,x2,y=W-0.221*H,W-0.077*H,0.085*H
    v=cap(x1,y,0.036*s,'#2a2b30')+f'<rect x="{x1-14}" y="{y-12}" width="18" height="15" rx="2" fill="none" stroke="#ddd" stroke-width="3"/><rect x="{x1-6}" y="{y-4}" width="18" height="15" rx="2" fill="#2a2b30" stroke="#ddd" stroke-width="3"/>'
    m=cap(x2,y,0.036*s,'#2a2b30')+''.join(f'<rect x="{x2-13}" y="{y+d-2}" width="26" height="4" rx="2" fill="#ddd"/>' for d in (-8,0,8))
    return v+m
styles={
 'Xbox':(lay(ST((LX,WY))+SH(L_LB,'#2a2b30','LB')+TR(L_LT,'#2a2b30','LT','L')+DP(L_DP,'#2a2b30','disc',0.095)+CL(L_L3,'#2a2b30','L3'),
             FACE(xs)+SH(R_RB,'#2a2b30','RB')+TR(R_RT,'#2a2b30','RT','R')+ST(R_RS,r=0.11)+CL(R_R3,'#2a2b30','R3'), VIEWMENU()),
        'left stick on the left wheel, A B X Y on the right wheel, bumpers and triggers above, d-pad and right stick below toward the middle (Microsoft\'s standard layout)'),
 'DualSense':(lay(ST((LX,WY),'#26272c')+SH(L_LB,'#2d2e34','L1')+TR(L_LT,'#2d2e34','L2','L')+DP(L_DP,'#3b3d44','segments',0.095)+CL(L_L3,'#2d2e34','L3'),
             FACE([('#3a3c44','x','#B7C6F0'),('#3a3c44','o','#F0B7BE'),('#3a3c44','s','#E8BDE0'),('#3a3c44','t','#B5E6D6')])+SH(R_RB,'#2d2e34','R1')+TR(R_RT,'#2d2e34','R2','R')+ST(R_RS,'#26272c',0.11)+CL(R_R3,'#2d2e34','R3'),
             pill(W/2,0.085*H,0.26*H,0.07*H,'#e9eaee','TOUCHPAD','#555')+pill(W/2-0.2*H,0.085*H,0.13*H,0.05*H,'#3b3d44','CREATE','#ddd')+pill(W/2+0.2*H,0.085*H,0.13*H,0.05*H,'#3b3d44','OPTIONS','#ddd')),
        'the same wheels with PlayStation shapes; touchpad, Create and Options along the top'),
 'Switch':(lay(ST((LX,WY),'#2b2c31')+SH(L_LB,'#26272c','L')+TR(L_LT,'#26272c','ZL','L')+DP(L_DP,'#2b2c31','cross',0.095)+CL(L_L3,'#26272c','L3'),
             FACE([('#2b2c31','B','#eee'),('#2b2c31','A','#eee'),('#2b2c31','Y','#eee'),('#2b2c31','X','#eee')])+SH(R_RB,'#26272c','R')+TR(R_RT,'#26272c','ZR','R')+ST(R_RS,'#2b2c31',0.11)+CL(R_R3,'#26272c','R3'),
             CL((W-0.221*H,0.085*H),'#2b2c31','−')+CL((W-0.077*H,0.085*H),'#2b2c31','+')),
        'Pro Controller on the same wheels, Nintendo\'s A on the right; − and + at the top'),
 'SNES':(lay(DP((LX,WY),'#2a2a2e','cross',0.16)+SH(L_LB,'#9b9ca6','L'),
             FACE([('#E9C433','B','#4a3a00'),('#D8382E','A','#fff'),('#2E9D52','Y','#fff'),('#2E5FC9','X','#fff')],rot=-20,r=0.15)+SH(R_RB,'#9b9ca6','R'),
             sel_start(c='#7d7e88',lc='#eee',rot=-20)),
        'the d-pad is the left wheel, the four colours the right wheel, L and R above, Select and Start low in the middle'),
 'NES':(lay(DP((LX,WY),'#1d1d20','cross',0.16),FACE2([('#C42E36','A','#fff'),('#C42E36','B','#fff')],-15,0.13), sel_start(c='#1d1d20',lc='#c9c9cf')),
        'big d-pad on the left wheel, the red A B on the right wheel, Select and Start low in the middle'),
 'Genesis':(lay(DP((LX,WY),'#1e1e22','disc',0.16), six(RX,WY,0.17*s), pill(W/2,0.90*H,0.17*H,0.055*H,'#2a2a30','START','#ddd')+pill(W/2,0.80*H,0.14*H,0.045*H,'#2a2a30','MODE','#bbb')),
        'round d-pad on the left wheel, A B C and X Y Z on the right wheel, Start in the middle'),
 'N64':(lay(ST((LX,WY),'#9a9ca6')+SH(L_LB,'#8d8f99','L')+TR(L_LT,'#5a5c66','Z','L')+DP(L_DP,'#8d8f99','cross',0.095),
             FACE2([('#2D57C2','A','#fff'),('#2B9A47','B','#fff')],35,0.12)+SH(R_RB,'#8d8f99','R')+cbtns(R_RS[0],R_RS[1],0.12*s),
             cap(W/2,0.90*H,0.045*s,'#C8302E','START','#fff',0.018*s)),
        'stick on the left wheel, Z where the trigger sits, the C buttons where the right stick goes, red Start in the middle'),
 'Game Boy':(lay(DP((LX,WY),'#1e1e22','cross',0.16), FACE2([('#A31F55','A','#f3d6e2'),('#A31F55','B','#f3d6e2')],-28,0.13), sel_start(c='#8a8a92',lc='#333',rot=-25)),
        'd-pad on the left wheel, the slanted magenta B A on the right wheel, Select and Start low in the middle'),
}
css='@font-face{font-family:Figtree;src:url(Figtree.ttf);font-weight:100 900}@font-face{font-family:Unbounded;src:url(Unbounded.ttf);font-weight:100 900}html,body{margin:0;overflow:hidden}'
SC=scene()
def frame(body,cap_=None):
    c=f'<div style="position:absolute;left:50%;transform:translateX(-50%);bottom:18px;font:700 25px Figtree;color:#fff;background:rgba(0,0,0,.6);padding:8px 18px;border-radius:12px;white-space:nowrap">{cap_}</div>' if cap_ else ''
    return f'<div style="position:relative;width:{W}px;height:{H}px"><svg width="{W}" height="{H}" viewBox="0 0 {W} {H}"><defs>{DEFS}</defs>{SC}<g opacity="{OP}">{body}</g></svg>{c}</div>'
names=list(styles)
for n,(b,cp) in styles.items():
    open(f'v4_{n.replace(" ","_")}.html','w').write(f'<html><head><style>{css}</style></head><body>{frame(b,n+": "+cp)}</body></html>')
cells=''.join(f'<div style="width:{W*.45}px;height:{H*.45+64}px;overflow:hidden"><div style="transform:scale(.45);transform-origin:0 0;width:{W}px">{frame(styles[n][0])}<div style="font:800 64px Unbounded;color:#fff;margin-top:16px">{n}</div></div></div>' for n in names)
open('v4_overview.html','w').write(f'<html><head><style>{css}body{{background:#16171B;padding:40px}}</style></head><body><div style="font:800 50px Unbounded;color:#fff;margin-bottom:8px">On-screen pad styles, v4</div><div style="font:600 26px Figtree;color:#B5BAC3;margin-bottom:28px">Placed on Microsoft\'s Xbox touch wheels (thumbs at rest, mid-height), real-looking per system, 78% see-through</div><div style="display:grid;grid-template-columns:repeat(4,{W*.45}px);gap:30px 26px">{cells}</div></body></html>')
