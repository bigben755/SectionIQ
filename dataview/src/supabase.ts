import { createClient } from '@supabase/supabase-js'

export const supabase = createClient(
  'https://ordpdwenpspiyfvovnvt.supabase.co',
  'sb_publishable_hBwrln84AI8bM2G2ACE9lw_FW-tmrJl',
  {
    auth: {
      persistSession: true,
      autoRefreshToken: true,
      detectSessionInUrl: true,
    },
  },
)
