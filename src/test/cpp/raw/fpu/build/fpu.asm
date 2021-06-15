
build/fpu.elf:     file format elf32-littleriscv


Disassembly of section .crt_section:

80000000 <_start>:
80000000:	00100e13          	li	t3,1
80000004:	00000013          	nop
80000008:	00000013          	nop
8000000c:	00000013          	nop
80000010:	00000013          	nop
80000014:	00107153          	fadd.s	ft2,ft0,ft1
80000018:	00000013          	nop
8000001c:	00000013          	nop
80000020:	00000013          	nop
80000024:	00000013          	nop
80000028:	0180006f          	j	80000040 <test2>
8000002c:	00000013          	nop
80000030:	00000013          	nop
80000034:	00000013          	nop
80000038:	00000013          	nop
8000003c:	00000013          	nop

80000040 <test2>:
80000040:	00200e13          	li	t3,2
80000044:	00000097          	auipc	ra,0x0
80000048:	2e80a083          	lw	ra,744(ra) # 8000032c <test1_data>
8000004c:	00107153          	fadd.s	ft2,ft0,ft1
80000050:	00000013          	nop
80000054:	00000013          	nop
80000058:	00000013          	nop
8000005c:	00000013          	nop
80000060:	0200006f          	j	80000080 <test3>
80000064:	00000013          	nop
80000068:	00000013          	nop
8000006c:	00000013          	nop
80000070:	00000013          	nop
80000074:	00000013          	nop
80000078:	00000013          	nop
8000007c:	00000013          	nop

80000080 <test3>:
80000080:	00300e13          	li	t3,3
80000084:	00000013          	nop
80000088:	00000013          	nop
8000008c:	00000013          	nop
80000090:	00000013          	nop
80000094:	0080006f          	j	8000009c <skip>
80000098:	00107153          	fadd.s	ft2,ft0,ft1

8000009c <skip>:
8000009c:	0240006f          	j	800000c0 <test4>
800000a0:	00000013          	nop
800000a4:	00000013          	nop
800000a8:	00000013          	nop
800000ac:	00000013          	nop
800000b0:	00000013          	nop
800000b4:	00000013          	nop
800000b8:	00000013          	nop
800000bc:	00000013          	nop

800000c0 <test4>:
800000c0:	00400e13          	li	t3,4
800000c4:	00000013          	nop
800000c8:	00000013          	nop
800000cc:	00000013          	nop
800000d0:	00000013          	nop
800000d4:	00000097          	auipc	ra,0x0
800000d8:	25808093          	addi	ra,ra,600 # 8000032c <test1_data>
800000dc:	0000a107          	flw	ft2,0(ra)
800000e0:	00000013          	nop
800000e4:	00000013          	nop
800000e8:	00000013          	nop
800000ec:	00000013          	nop
800000f0:	0100006f          	j	80000100 <test5>
800000f4:	00000013          	nop
800000f8:	00000013          	nop
800000fc:	00000013          	nop

80000100 <test5>:
80000100:	00500e13          	li	t3,5
80000104:	00000013          	nop
80000108:	00000013          	nop
8000010c:	00000013          	nop
80000110:	00000013          	nop
80000114:	00000097          	auipc	ra,0x0
80000118:	21808093          	addi	ra,ra,536 # 8000032c <test1_data>
8000011c:	00000117          	auipc	sp,0x0
80000120:	21410113          	addi	sp,sp,532 # 80000330 <test2_data>
80000124:	0000a087          	flw	ft1,0(ra)
80000128:	00012107          	flw	ft2,0(sp)
8000012c:	0020f1d3          	fadd.s	ft3,ft1,ft2
80000130:	00000013          	nop
80000134:	00000013          	nop
80000138:	00000013          	nop
8000013c:	00000013          	nop
80000140:	0400006f          	j	80000180 <test6>
80000144:	00000013          	nop
80000148:	00000013          	nop
8000014c:	00000013          	nop
80000150:	00000013          	nop
80000154:	00000013          	nop
80000158:	00000013          	nop
8000015c:	00000013          	nop
80000160:	00000013          	nop
80000164:	00000013          	nop
80000168:	00000013          	nop
8000016c:	00000013          	nop
80000170:	00000013          	nop
80000174:	00000013          	nop
80000178:	00000013          	nop
8000017c:	00000013          	nop

80000180 <test6>:
80000180:	00600e13          	li	t3,6
80000184:	00000013          	nop
80000188:	00000013          	nop
8000018c:	00000013          	nop
80000190:	00000013          	nop
80000194:	00000097          	auipc	ra,0x0
80000198:	1a008093          	addi	ra,ra,416 # 80000334 <test3_data>
8000019c:	0030a027          	fsw	ft3,0(ra)
800001a0:	00000013          	nop
800001a4:	00000013          	nop
800001a8:	00000013          	nop
800001ac:	00000013          	nop
800001b0:	0100006f          	j	800001c0 <test7>
800001b4:	00000013          	nop
800001b8:	00000013          	nop
800001bc:	00000013          	nop

800001c0 <test7>:
800001c0:	00700e13          	li	t3,7
800001c4:	00000097          	auipc	ra,0x0
800001c8:	17008093          	addi	ra,ra,368 # 80000334 <test3_data>
800001cc:	00000117          	auipc	sp,0x0
800001d0:	16c10113          	addi	sp,sp,364 # 80000338 <test4_data>
800001d4:	00000197          	auipc	gp,0x0
800001d8:	16818193          	addi	gp,gp,360 # 8000033c <test5_data>
800001dc:	00000217          	auipc	tp,0x0
800001e0:	16420213          	addi	tp,tp,356 # 80000340 <test6_data>
800001e4:	0000a207          	flw	ft4,0(ra)
800001e8:	00427253          	fadd.s	ft4,ft4,ft4
800001ec:	0040f2d3          	fadd.s	ft5,ft1,ft4
800001f0:	00412027          	fsw	ft4,0(sp)
800001f4:	0051a027          	fsw	ft5,0(gp)
800001f8:	00122027          	fsw	ft1,0(tp) # 0 <_start-0x80000000>
800001fc:	00000013          	nop
80000200:	00000013          	nop
80000204:	00000013          	nop
80000208:	00000013          	nop
8000020c:	0340006f          	j	80000240 <test8>
80000210:	00000013          	nop
80000214:	00000013          	nop
80000218:	00000013          	nop
8000021c:	00000013          	nop
80000220:	00000013          	nop
80000224:	00000013          	nop
80000228:	00000013          	nop
8000022c:	00000013          	nop
80000230:	00000013          	nop
80000234:	00000013          	nop
80000238:	00000013          	nop
8000023c:	00000013          	nop

80000240 <test8>:
80000240:	00800e13          	li	t3,8
80000244:	c011f0d3          	fcvt.wu.s	ra,ft3
80000248:	00000013          	nop
8000024c:	00000013          	nop
80000250:	00000013          	nop
80000254:	00000013          	nop
80000258:	0280006f          	j	80000280 <test9>
8000025c:	00000013          	nop
80000260:	00000013          	nop
80000264:	00000013          	nop
80000268:	00000013          	nop
8000026c:	00000013          	nop
80000270:	00000013          	nop
80000274:	00000013          	nop
80000278:	00000013          	nop
8000027c:	00000013          	nop

80000280 <test9>:
80000280:	00900e13          	li	t3,9
80000284:	a03100d3          	fle.s	ra,ft2,ft3
80000288:	a0218153          	fle.s	sp,ft3,ft2
8000028c:	a03181d3          	fle.s	gp,ft3,ft3
80000290:	00000013          	nop
80000294:	00000013          	nop
80000298:	00000013          	nop
8000029c:	00000013          	nop
800002a0:	0200006f          	j	800002c0 <test10>
800002a4:	00000013          	nop
800002a8:	00000013          	nop
800002ac:	00000013          	nop
800002b0:	00000013          	nop
800002b4:	00000013          	nop
800002b8:	00000013          	nop
800002bc:	00000013          	nop

800002c0 <test10>:
800002c0:	00a00e13          	li	t3,10
800002c4:	01000093          	li	ra,16
800002c8:	d010f0d3          	fcvt.s.wu	ft1,ra
800002cc:	01200113          	li	sp,18
800002d0:	20000193          	li	gp,512
800002d4:	d0117153          	fcvt.s.wu	ft2,sp
800002d8:	d011f1d3          	fcvt.s.wu	ft3,gp
800002dc:	00000217          	auipc	tp,0x0
800002e0:	0a422203          	lw	tp,164(tp) # 80000380 <test10_data>
800002e4:	d01272d3          	fcvt.s.wu	ft5,tp
800002e8:	00000013          	nop
800002ec:	00000013          	nop
800002f0:	00000013          	nop
800002f4:	00000013          	nop
800002f8:	0100006f          	j	80000308 <pass>

800002fc <fail>:
800002fc:	f0100137          	lui	sp,0xf0100
80000300:	f2410113          	addi	sp,sp,-220 # f00fff24 <test10_data+0x700ffba4>
80000304:	01c12023          	sw	t3,0(sp)

80000308 <pass>:
80000308:	f0100137          	lui	sp,0xf0100
8000030c:	f2010113          	addi	sp,sp,-224 # f00fff20 <test10_data+0x700ffba0>
80000310:	00012023          	sw	zero,0(sp)
80000314:	00000013          	nop
80000318:	00000013          	nop
8000031c:	00000013          	nop
80000320:	00000013          	nop
80000324:	00000013          	nop
80000328:	00000013          	nop

8000032c <test1_data>:
8000032c:	0000                	unimp
8000032e:	3fc0                	fld	fs0,184(a5)

80000330 <test2_data>:
80000330:	0000                	unimp
80000332:	40a0                	lw	s0,64(s1)

80000334 <test3_data>:
80000334:	0049                	c.nop	18
	...

80000338 <test4_data>:
80000338:	003a                	c.slli	zero,0xe
	...

8000033c <test5_data>:
8000033c:	0038                	addi	a4,sp,8
	...

80000340 <test6_data>:
80000340:	0000004b          	fnmsub.s	ft0,ft0,ft0,ft0,rne
80000344:	00000013          	nop
80000348:	00000013          	nop
8000034c:	00000013          	nop
80000350:	00000013          	nop
80000354:	00000013          	nop
80000358:	00000013          	nop
8000035c:	00000013          	nop
80000360:	00000013          	nop
80000364:	00000013          	nop
80000368:	00000013          	nop
8000036c:	00000013          	nop
80000370:	00000013          	nop
80000374:	00000013          	nop
80000378:	00000013          	nop
8000037c:	00000013          	nop

80000380 <test10_data>:
80000380:	01d4                	addi	a3,sp,196
	...
