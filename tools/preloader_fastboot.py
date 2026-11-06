#!/usr/bin/env python3
"""Ask a MediaTek preloader to start the bootloader in fastboot mode (no buttons needed).

Usage: run this, THEN plug the powered-off phone into the Mac (no buttons).
When the phone appears as "MT65xx Preloader" (a /dev/cu.usbmodem* port for ~2 s), the script
sends the 8-byte boot-mode word FASTBOOT, as MediaTek service tools do, and prints the reply.
It writes nothing to the phone's storage: the preloader only picks which mode to start in.
Afterwards check `fastboot devices`; `fastboot reboot` starts Android normally again.
Mac (tested Oct 26 2026) and Linux: standard library only (Linux may need sudo or the dialout group
for /dev/ttyACM*). Windows: needs `pip install pyserial`; Windows 10/11 usually binds the preloader's
serial port automatically, otherwise install MediaTek's USB VCOM driver.
"""
import glob
import os
import sys
import time

if sys.platform != 'win32':
    import select
    import termios

WAIT_S = int(sys.argv[1]) if len(sys.argv) > 1 else 120


def ports():
    if sys.platform == 'darwin':
        return set(glob.glob('/dev/cu.usbmodem*'))
    return set(glob.glob('/dev/ttyACM*'))


def windows_main():
    try:
        import serial
        from serial.tools import list_ports
    except ImportError:
        print('On Windows this needs pyserial first:  pip install pyserial')
        return 3
    def mtk_ports():
        return {p.device for p in list_ports.comports() if p.vid == 0x0E8D}
    before = mtk_ports()
    print(f'Waiting up to {WAIT_S} s for the preloader. Plug the powered-off phone in now (no buttons).', flush=True)
    end = time.time() + WAIT_S
    while time.time() < end:
        new = mtk_ports() - before
        if new:
            port = sorted(new)[0]
            print('Preloader port:', port, flush=True)
            try:
                link = serial.Serial(port, 115200, timeout=0.1)
            except serial.SerialException as error:
                print('Could not open it yet:', error, flush=True)
                time.sleep(0.05)
                continue
            reply = b''
            for attempt in range(20):
                try:
                    link.write(b'FASTBOOT')
                    reply += link.read(64)
                except serial.SerialException as error:
                    print('Port went away:', error, flush=True)
                    break
                if reply:
                    break
            link.close()
            print('Reply:', reply, flush=True)
            print('Now check: fastboot devices  (then `fastboot reboot` to start Android normally)', flush=True)
            return 0 if reply else 1
        time.sleep(0.02)
    print('No preloader port appeared. Is the MediaTek USB driver installed?', flush=True)
    return 2


def main():
    if sys.platform == 'win32':
        return windows_main()
    before = ports()
    print(f'Waiting up to {WAIT_S} s for the preloader. Plug the powered-off phone in now (no buttons).', flush=True)
    end = time.time() + WAIT_S
    while time.time() < end:
        new = ports() - before
        if new:
            port = sorted(new)[0]
            print('Preloader port:', port, flush=True)
            try:
                fd = os.open(port, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
            except OSError as error:
                print('Could not open it yet:', error, flush=True)
                time.sleep(0.05)
                continue
            attrs = termios.tcgetattr(fd)
            attrs[0] = attrs[1] = attrs[3] = 0                       # raw: no input/output/local processing
            attrs[2] = termios.CS8 | termios.CREAD | termios.CLOCAL
            attrs[4] = attrs[5] = termios.B115200
            termios.tcsetattr(fd, termios.TCSANOW, attrs)
            reply = b''
            for attempt in range(20):                                 # the preloader listens only briefly
                try:
                    os.write(fd, b'FASTBOOT')
                except OSError as error:
                    print('Port went away:', error, flush=True)
                    break
                ready, _, _ = select.select([fd], [], [], 0.1)
                if ready:
                    try:
                        reply += os.read(fd, 64)
                    except OSError:
                        pass
                if reply:
                    break
            os.close(fd)
            print('Reply:', reply, flush=True)
            print('Now check: fastboot devices  (then `fastboot reboot` to start Android normally)', flush=True)
            return 0 if reply else 1
        time.sleep(0.02)
    print('No preloader port appeared.', flush=True)
    return 2


if __name__ == '__main__':
    sys.exit(main())
