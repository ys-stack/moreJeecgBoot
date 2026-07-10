import time
import pandas as pd
from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from bs4 import BeautifulSoup

def scrape_dongchedi_specs(series_id="5660"):
    """
    爬取懂车帝指定车系（默认极氪007：5660）的配置和价格，并导出为 Excel
    """
    url = f"https://www.dongchedi.com/auto/params-pc?series_id={series_id}"
    
    # 1. 设置无头浏览器参数，规避常规检测
    chrome_options = Options()
    chrome_options.add_argument("--headless")  # 开启无头模式
    chrome_options.add_argument("--disable-gpu")
    chrome_options.add_argument("--window-size=1920,1080")
    chrome_options.add_argument("--no-sandbox")
    chrome_options.add_argument("--disable-dev-shm-usage")
    # 伪装 User-Agent 防止被直接拦截
    chrome_options.add_argument("user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
    
    print("正在启动 Chrome 浏览器...")
    driver = webdriver.Chrome(options=chrome_options)
    
    try:
        print(f"正在加载懂车帝参数配置页: {url}")
        driver.get(url)
        
        # 2. 等待配置表格加载完成
        WebDriverWait(driver, 15).until(
            EC.presence_of_element_located((By.CLASS_URI, "param-table")) or 
            EC.presence_of_element_located((By.TAG_NAME, "table"))
        )
        
        # 适当休眠，等待动态 JS 渲染完毕
        time.sleep(3)
        
        # 3. 获取渲染后的 HTML 并解析
        html = driver.page_source
        soup = BeautifulSoup(html, "html.parser")
        
        # 懂车帝 PC 端参数页通常将车型名字放在表头，参数项在首列
        # 我们寻找所有的表格元素进行解析
        tables = soup.find_all("table")
        if not tables:
            print("未能找到参数配置表格，可能是页面结构发生变化或触发了防爬验证。")
            return
            
        print("开始解析网页数据...")
        
        # 提取车型名称（一般在 table 的 thread 或第一行 tr）
        models = []
        specs_data = {}
        
        # 遍历解析表格
        for idx, table in enumerate(tables):
            rows = table.find_all("tr")
            if not rows:
                continue
                
            for row in rows:
                cols = [td.get_text(strip=True) for td in row.find_all(["td", "th"])]
                if not cols:
                    continue
                
                param_name = cols[0]  # 第一列一般是“参数项名称”
                param_values = cols[1:]  # 后续列是各个车型的对应参数值
                
                # 如果是第一行，提取车型名称
                if "车型" in param_name or "款型" in param_name or idx == 0 and not models:
                    models = param_values
                    continue
                
                # 记录我们关心的核心配置
                specs_data[param_name] = param_values
                
        # 4. 用 Pandas 进行数据结构化整理
        if not models:
            # 备用方案：如果未提取到车型，自定义列名
            columns = [f"配置{i+1}" for i in range(len(list(specs_data.values())[0]))]
        else:
            columns = models

        df = pd.DataFrame.from_dict(specs_data, orient='index', columns=columns)
        
        # 转置表格，使车型作为行，配置参数作为列
        df = df.T
        df.index.name = "车型名称"
        df = df.reset_index()
        
        # 5. 过滤筛选出我们关心的 “2026款” 车型
        df_2026 = df[df["车型名称"].str.contains("2026", na=False)]
        
        if df_2026.empty:
            print("未能过滤出 2026 款车型，输出全部在售车型数据。")
            df_2026 = df
            
        # 6. 保存为 Excel 和 CSV
        output_excel = "zeekr_007_2026_specs.xlsx"
        output_csv = "zeekr_007_2026_specs.csv"
        
        df_2026.to_excel(output_excel, index=False)
        df_2026.to_csv(output_csv, index=False, encoding="utf-8-sig")
        
        print(f"数据爬取并处理成功！")
        print(f"Excel文件已保存至: {output_excel}")
        print(f"CSV文件已保存至: {output_csv}")
        
        # 打印简易控制台表格
        print("\n=== 2026款极氪007配置价格表 ===")
        # 只筛选几个核心指标进行打印展示
        core_cols = ["车型名称", "官方指导价(万元)", "厂商指导价(元)", "电池容量(kWh)", "CLTC纯电续航里程(km)", "百公里加速时间(s)"]
        existing_cols = [c for c in core_cols if c in df_2026.columns]
        if not existing_cols:
            existing_cols = df_2026.columns[:6]
        print(df_2026[existing_cols].to_markdown(index=False))

    except Exception as e:
        print(f"爬取过程中发生异常: {e}")
    finally:
        driver.quit()

if __name__ == "__main__":
    # 执行爬取
    scrape_dongchedi_specs()
