import sys, os, tarfile, io, paramiko
sys.stdout.reconfigure(encoding='utf-8')

print("Connecting to Proxmox host 192.168.100.19...")
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.100.19', username='root', password='guardian8')

# Create tar in memory
tar_stream = io.BytesIO()
with tarfile.open(fileobj=tar_stream, mode='w:gz') as tar:
    local_dir = os.path.join(os.path.dirname(__file__), 'web-app')
    for root, dirs, files in os.walk(local_dir):
        if 'cache' in root.split(os.sep):
            continue
        for f in files:
            full_path = os.path.join(root, f)
            rel_path = os.path.relpath(full_path, local_dir)
            tar.add(full_path, arcname=rel_path)
            print(f"Added to tar: {rel_path}")

tar_bytes = tar_stream.getvalue()
print(f"Tar archive created: {len(tar_bytes)} bytes")

# Upload to Proxmox /tmp/komik-web.tar.gz via SFTP
sftp = ssh.open_sftp()
with sftp.file('/tmp/komik-web.tar.gz', 'wb') as remote_file:
    remote_file.write(tar_bytes)
sftp.close()
print("Uploaded /tmp/komik-web.tar.gz to Proxmox")

# Push into LXC 123 and extract
commands = [
    'pct push 123 /tmp/komik-web.tar.gz /tmp/komik-web.tar.gz',
    'pct exec 123 -- tar -xzf /tmp/komik-web.tar.gz -C /var/www/komik/',
    'pct exec 123 -- chown -R www-data:www-data /var/www/komik',
    'pct exec 123 -- chown -R www-data:www-data /var/cache/komik',
    'pct exec 123 -- systemctl restart komik-reader',
    'pct exec 123 -- systemctl status komik-reader --no-pager'
]

for cmd in commands:
    print(f"Executing: {cmd}")
    stdin, stdout, stderr = ssh.exec_command(cmd)
    out = stdout.read().decode('utf-8', errors='replace')
    err = stderr.read().decode('utf-8', errors='replace')
    if out.strip():
        print(out.strip())
    if err.strip():
        print("ERR:", err.strip())

ssh.close()
print("Deployment completed successfully!")
