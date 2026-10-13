const mysql = require('mysql2/promise');
(async () => {
  // 凭据改从环境变量读取,严禁把生产库账号密码写进仓库。
  // 用法: CY_DB_HOST=... CY_DB_USER=... CY_DB_PASSWORD=... node scripts/check_data.js
  if (!process.env.CY_DB_HOST || !process.env.CY_DB_PASSWORD) {
    console.error('缺少数据库环境变量:请设置 CY_DB_HOST / CY_DB_USER / CY_DB_PASSWORD / CY_DB_NAME');
    process.exit(1);
  }
  const conn = await mysql.createConnection({
    host: process.env.CY_DB_HOST,
    user: process.env.CY_DB_USER || 'chengyinhubdb',
    password: process.env.CY_DB_PASSWORD,
    database: process.env.CY_DB_NAME || 'chengyinhubdb',
  });

  const [rec] = await conn.execute('SELECT id, name, is_recommend FROM cms_topic WHERE del_flag=0 AND status=1 AND user_status=1 LIMIT 20');
  console.log('Topics (published, not deleted):');
  rec.forEach(r => console.log('  id='+r.id, r.name, 'is_recommend='+r.is_recommend));

  const [banners] = await conn.execute('SELECT COUNT(*) as cnt FROM cms_banner WHERE del_flag=0');
  console.log('Banners:', banners[0].cnt);

  const tables = await conn.query("SHOW TABLES LIKE '%activ%'");
  console.log('Activity tables:', tables[0].map(t => Object.values(t)[0]).join(', '));

  await conn.end();
})().catch(e => console.error(e));
