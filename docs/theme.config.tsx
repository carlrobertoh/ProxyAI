import React from 'react'
import {DocsThemeConfig} from 'nextra-theme-docs'

const config: DocsThemeConfig = {
  logo: <span>ProxyAI</span>,
  project: {
    link: 'https://github.com/carlrobertoh/ProxyAI',
  },
  chat: {
    link: 'https://discord.gg/8dTGGrwcnR',
  },
  docsRepositoryBase: 'https://github.com/carlrobertoh/ProxyAI',
  footer: {
    text: 'ProxyAI Documentation',
  },
  useNextSeoProps: () => ({titleTemplate: '%s – ProxyAI'})
}

export default config
